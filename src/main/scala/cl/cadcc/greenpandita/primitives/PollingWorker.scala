package cl.cadcc.greenpandita.primitives

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.{MapRef, Supervisor}
import cats.effect.syntax.all.*
import cats.effect.*
import cats.syntax.all.*
import cron4s.CronExpr
import cron4s.lib.javatime.given
import cron4s.syntax.all.*

import java.time.{Instant, ZoneId}
import scala.concurrent.duration.Duration
import scala.jdk.DurationConverters.JavaDurationOps

trait PollingWorker[F[_]] {
  def runEvery(duration: Duration, hot: Boolean = false)(job: Unique.Token => F[Unit]): F[JobHandle[F]]
  def runEvery(cron: CronExpr)(job: Unique.Token => F[Unit]): F[JobHandle[F]]
}

trait JobHandle[F[_]] {
  def triggerNow(): F[Boolean]
  def stop(): F[Boolean]
}

object PollingWorker {

  def apply[F[_]: {Temporal, Sync}]: Resource[F, PollingWorker[F]] =
    for {
      supervisor <- Supervisor[F]
      mapRef <- MapRef.ofConcurrentHashMap[F, Unique.Token, Deferred[F, JobData[F]]]().toResource
    } yield PollingWorkerImpl[F](supervisor, mapRef)

  private case class JobData[F[_]](id: Unique.Token, fiber: Fiber[F, Throwable, Unit], event: Ref[F, Deferred[F, Unit]])

  private class PollingWorkerImpl[F[_]: Temporal as F](supervisor: Supervisor[F], jobsRunning: MapRef[F, Unique.Token, Option[Deferred[F, JobData[F]]]]) extends PollingWorker[F] {

    private val localTZ = ZoneId.systemDefault()

    override def runEvery(duration: Duration, hot: Boolean = false)(job: Unique.Token => F[Unit]): F[JobHandle[F]] =
      startJob(job){ _ => duration}

    override def runEvery(cron: CronExpr)(job: Unique.Token => F[Unit]): F[JobHandle[F]] =
      startJob(job){ now => now.until(cron.next(now.atZone(localTZ)).get.toInstant).toScala }

    private def startJob(job: Unique.Token => F[Unit])(duration: Instant => Duration): F[JobHandle[F]] =
      for {
        id <- F.unique
        dfrData <- F.deferred[JobData[F]]
        dfrEvent <- F.deferred[Unit]
        eventRef <- F.ref(dfrEvent)
        handle <- F.uncancelable { poll =>
          for {
            _ <- jobsRunning(id).set(dfrData.some)
            fiber <- supervisor.supervise(wrapJob(id, dfrData, duration, job(id), false))
            data = JobData(id, fiber, eventRef)
            _ <- dfrData.complete(data)
          } yield JobHandleImpl[F](jobsRunning(id))
        }
      } yield handle

    private def wrapJob(
      id: Unique.Token,
      jobData: Deferred[F, JobData[F]],
      duration: Instant => Duration,
      job: F[Unit],
      hot: Boolean
    ): F[Unit] =
      val wait =
        for {
          dfrEvent <- F.deferred[Unit]
          data <- jobData.get
          _ <- data.event.set(dfrEvent)
          now <- F.realTimeInstant
          _ <- F.race(dfrEvent.get, F.sleep(duration(now)))
        } yield ()
      val body =
        if hot then job *> wait
        else wait *> job
      body.foreverM
  }

  private class JobHandleImpl[F[_]: MonadCancelThrow as F](ref: Ref[F, Option[Deferred[F, JobData[F]]]]) extends JobHandle[F] {
    override def triggerNow(): F[Boolean] =
      ref.get.flatMap {
        case Some(dfr) =>
          for {
            data <- dfr.get
            dfrEvent <- data.event.get
            b <- dfrEvent.complete(())
          } yield b
        case None => false.pure
      }

    override def stop(): F[Boolean] =
      ref.get.flatMap {
        case Some(dfr) =>
          for {
            data <- dfr.get
            b <- F.uncancelable { poll =>
              data.fiber.cancel *> ref.tryUpdate(_ => None)
            }
          } yield b
        case None => false.pure
      }
  }
}
