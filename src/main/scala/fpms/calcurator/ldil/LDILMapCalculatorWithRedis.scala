package fpms.calcurator.ldil

import scala.util.Try

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.ContextShift
import cats.effect.concurrent.MVar2
import cats.implicits._
import com.github.sh4869.semver_parser
import com.typesafe.scalalogging.LazyLogging
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.RedisCommands

import fpms.LibraryPackage
import fpms.redis.RedisConf
import fpms.redis.RedisLog
import fpms.repository.LibraryPackageRepository
import cats.effect.concurrent.Semaphore

class LDILMapCalculatorWithRedis[F[_]](
    repo: LibraryPackageRepository[F],
    conf: RedisConf,
    mvar: MVar2[F, Map[Int, Seq[Int]]]
)(
    implicit F: ConcurrentEffect[F],
    P: Parallel[F],
    cs: ContextShift[F]
) extends LDILMapCalculator[F]
    with RedisLog[F]
    with LazyLogging {
  import fpms.redis.RedisDataConversion._
  protected val X = implicitly

  def init: F[LDILMap] = new LDILMapCalculatorOnMemory[F].init

  // TODO: コメント・実装の整理
  def update(adds: Seq[LibraryPackage]): F[LDILMap] = {
    val sortedAdds = adds.sortWith((a, b) => if (a.name == b.name) a.version < b.version else a.name > b.name)
    val result = for {
      empty <- mvar.isEmpty
      beforeMap <- if (empty) createInitialMapFromRedis() else mvar.read
      _ <- F.pure(logger.info("get before values"))
      x <- sortedAdds
        .map(v => createShouldUpdateList(v, beforeMap))
        .parSequence
        .map(
          _.reduce((a, b) =>
            (a.keySet ++ b.keySet)
              .map(key => key -> (a.get(key).getOrElse(Map.empty) ++ b.get(key).getOrElse(Map.empty)))
              .toMap
          )
        )
      _ <- F.pure(logger.info("all calc end"))
    } yield beforeMap.map {
      case (key, seq) =>
        if (x.contains(key))
          key -> (seq.filter(v => !x.get(key).get.keySet.contains(v)) ++ x.get(key).get.values.toSeq).toList
        else key -> seq.toList
    }
    for {
      v <- result
      _ <- mvar.tryTake
      _ <- mvar.put(v)
    } yield v
  }

  // TODO: コメントを書く
  private def createShouldUpdateList(p: LibraryPackage, idMap: Map[Int, Seq[Int]]): F[Map[Int, Map[Int, Int]]] = {
    for {
      semaphore <- Semaphore(16)
      _ <- F.pure(logger.info(s"create should update list: ${p.name}@${p.version.original}"))
      // p.name のパッケージに依存しそのパッケージのバージョン条件を p.version が満たすものを探す
      ts <- repo
        .findByDeps(p.name)
        .map(_.filter(_.deps.get(p.name).exists(v => Try { semver_parser.Range(v).valid(p.version) }.getOrElse(false))))
      _ <- F.pure(logger.info(s"get candide list of ${p.name}@${p.version.original} / ${ts.length}"))
      x <- ts.map { v =>
        idMap
          .get(v.id)
          .fold(F.pure[Option[(Int, Map[Int, Int])]](None))(list =>
            for {
              _ <- semaphore.acquire
              x <- repo.findByIds(list.toList).map {
                // 同じ名前で同じバージョンのものを探し
                _.filter(t => t.name == p.name && t.version < p.version).headOption.map(z => v.id -> Map(z.id -> p.id))
              }
              _ <- semaphore.release
            } yield x
          )
      }.parSequence.map(_.flatten.toMap)
      _ <- F.pure(logger.info(s"end should update list: ${p.name}@${p.version.original}"))
    } yield x
  }

  private def createInitialMapFromRedis(): F[Map[Int, Seq[Int]]] = {
    Redis[F].utf8(s"redis://${conf.host}:${conf.port}").use { cmd: RedisCommands[F, String, String] =>
      for {
        max <- repo.getMaxId
        z <- {
          val groupedRange = Range(0, max).grouped(max / 8).zipWithIndex
          groupedRange.map {
            case (v, i) => {
              F.async[Map[Int, Seq[Int]]](cb => {
                val x = v.toList
                  .grouped(100)
                  .map { v => F.toIO(getSeqFromRedis(cmd, v.map(x => s"$LDIL_REDIS_PREFIX$x").toSet)).unsafeRunSync() }
                  .reduce((a, b) => a ++ b)
                logger.info(s"end $i")
                cb(Right(x))
              })
            }
          }.toList.parSequence
        }
      } yield z.reduce((a, b) => a ++ b)
    }
  }

  private def getSeqFromRedis(cmd: RedisCommands[F, String, String], set: Set[String]): F[Map[Int, Seq[Int]]] =
    cmd
      .mGet(set)
      .map(_.map[Int, Seq[Int]] {
        case (key, value) => key.split(LDIL_REDIS_PREFIX)(1).toInt -> value.splitToSeq
      }.toMap)
}