package cl.cadcc.greenpandita

import cats.data.OptionT
import cats.effect.std.{MapRef, PQueue, Supervisor}
import cats.effect.{Fiber, Resource, Sync, Temporal, Unique}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import cron4s.CronExpr
import cron4s.syntax.all.*
import cron4s.lib.javatime.given

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneId}
import scala.concurrent.duration.Duration
import scala.jdk.DurationConverters.JavaDurationOps

trait PollingWorker[F[_]] {
  def runEvery(duration: Duration, hot: Boolean = false)(job: Unique.Token => F[Unit]): F[Unique.Token]
  def runEvery(cron: CronExpr)(job: Unique.Token => F[Unit]): F[Unique.Token]

  def cancel(id: Unique.Token): F[Unit]
}

object PollingWorker {

  def apply[F[_]: {Temporal, Sync}]: Resource[F, PollingWorker[F]] =
    for {
      supervisor <- Supervisor[F]
      mapRef <- MapRef.ofConcurrentHashMap[F, Unique.Token, Fiber[F, Throwable, Unit]]().toResource
    } yield PollingWorkerImpl[F](supervisor, mapRef)

  private case class QueuedJob(scheduledAt: Instant, id: Unique.Token)

  private class PollingWorkerImpl[F[_]: Temporal as F](supervisor: Supervisor[F], jobsRunning: MapRef[F, Unique.Token, Option[Fiber[F, Throwable, Unit]]]) extends PollingWorker[F] {

    private val localTZ = ZoneId.systemDefault()

    override def runEvery(duration: Duration, hot: Boolean = false)(job: Unique.Token => F[Unit]): F[Unique.Token] =
      for {
        id <- F.unique
        fiber <- supervisor.supervise(wrapJob(_ => duration, job(id), hot))
        _ <- jobsRunning(id).set(Some(fiber))
      } yield id

    override def runEvery(cron: CronExpr)(job: Unique.Token => F[Unit]): F[Unique.Token] =
      for {
        id <- F.unique
        fiber <- supervisor.supervise(wrapJob(now => now.until(cron.next(now.atZone(localTZ)).get.toInstant).toScala, job(id), false))
        _ <- jobsRunning(id).set(Some(fiber))
      } yield id

    override def cancel(id: Unique.Token): F[Unit] =
      for {
        fiber <- OptionT(jobsRunning(id).get).getOrRaise(IllegalArgumentException("There is no job with that token."))
        _ <- fiber.cancel
        _ <- jobsRunning(id).set(None)
      } yield ()

    private def wrapJob(duration: Instant => Duration, job: F[Unit], hot: Boolean): F[Unit] =
      val body =
        if hot then
          for {
            _ <- job
            now <- F.realTimeInstant
            _ <- F.sleep(duration(now))
          } yield ()
        else
          for {
            now <- F.realTimeInstant
            _ <- F.sleep(duration(now))
            _ <- job
          } yield ()
      body.foreverM
  }
}
