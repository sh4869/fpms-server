package fpms

import java.util.concurrent.Executors
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import fpms.VersionCondition._
import fs2.Stream
import fs2.concurrent.Queue
import fs2.concurrent.Topic
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext


class PackageUpdateSubscriber[F[_]](
  val name: String,
  containers: MVar[F, Set[PackageDepsContainer[F]]],
  val queue: Queue[F, PackageUpdateEvent],
  topic: Topic[F, PackageUpdateEvent],
  val alreadySubscribed: MVar[F, Set[String]]
)(
  implicit F: ConcurrentEffect[F]
) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def deleteAllinQueue(): Unit = {
    while (F.toIO(queue.tryDequeue1).unsafeRunSync().isDefined) {}
  }

  def addNewVersion(container: PackageDepsContainer[F]): F[Unit] =
    for {
      _ <- F.pure(logger.info(s"new version:${container.info.name}@${container.info.version.original}"))
      newc <- containers.take.map(_ + container)
      _ <- containers.put(newc)
      deps <- container.dependencies
      _ <- topic.publish1(AddNewVersion(container.info, deps))
    } yield ()

  def getAllVersion: F[Seq[PackageInfo]] =
    containers.read.map(_.map(_.info).toSeq)


  def getDependencies(condition: VersionCondition): F[Option[DepResult]] =
    getLatestVersion(condition).flatMap(_.fold(F.pure[Option[DepResult]](None))(e =>
      for {
        dep <- e.dependencies
        info <- e.packdepInfo
      } yield Some(DepResult(info, dep))
    ))


  def getLatestVersion(condition: VersionCondition): F[Option[PackageDepsContainer[F]]] =
    containers.read.map(_.filter(e => condition.valid(e.info.version)).toSeq.sortWith((x, y) => x.info.version > y.info.version).headOption)

  private def onAddNewVersion(event: AddNewVersion): Stream[F, Unit] =
    Stream.eval(F.pure(logger.info(s"[event]: Add new version in dep(${event.packageInfo.name}@${event.packageInfo.version.original}) at $name")))
      .flatMap(_ => readContainer)
      .evalMap(c => c.addNewVersion(event.packageInfo, event.dependencies).map(result => (c, result)))
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  private def onUpdateDependencies(event: UpdateDependency): Stream[F, Unit] =
    Stream.eval(F.pure(logger.info(s"[event]: update deps in dep(${event.packageInfo.name}@${event.packageInfo.version.original}) at $name")))
      .flatMap(_ => readContainer)
      .evalMap(v => v.updateDependencies(event.packageInfo, event.dependencies).map(result => (v, result)))
      .filter(_._2)
      .evalMap(x => x._1.dependencies.map(deps => UpdateDependency(x._1.info, deps)))
      .through(topic.publish)

  def readContainer: Stream[F, PackageDepsContainer[F]] =
    Stream.eval(containers.read).flatMap(x => Stream.apply(x.toSeq: _*))

  def start: F[Unit] = queue.dequeue.flatMap(_ match {
    case e: AddNewVersion => onAddNewVersion(e)
    case e: UpdateDependency => onUpdateDependencies(e)
    case _ => Stream(())
  }).compile.drain
}

case class DepResult(pack: PackageDepInfo, deps: Seq[PackageDepInfo])
