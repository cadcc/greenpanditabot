package cl.cadcc.greenpandita.primitives

import cats.effect.kernel.{DeferredSink, DeferredSource, Outcome, Poll}
import cats.effect.syntax.all.*
import cats.effect.{Clock, Concurrent, Deferred, Ref}
import cats.syntax.all.*

import java.time.Instant

trait RefreshableCache[F[_], V] {
  /**
   *
   */
  def get: F[V]
}

trait Refreshable[F[_], V] {
  def isValid(now: Instant, v: V): Boolean
  def refresh(old: V): F[V]
}

object RefreshableCache {

  /**
   * Creates an instance of
   *
   * @param init Initial value of the cache
   * @param isValid Receives current `Clock[F].realtimeInstant` time
   * @param refresh The effect of
   * @tparam F Effectful context
   * @tparam V Type of the storage
   * @return
   */
  def apply[F[_]: {Concurrent as F, Clock}, V](init: V)(using ref : Refreshable[F, V]): F[RefreshableCache[F, V]] =
    for {
      cache <- F.ref[State[F, V]](Valid(init))
    } yield RefreshableCacheImpl(cache, ref.isValid, ref.refresh)

  private sealed trait State[F[_], V]
  private case class Valid[F[_], V](value: V) extends State[F, V]
  private case class Refreshing[F[_], V](get: DeferredSource[F, F[V]]) extends State[F, V]
  private case class Failed[F[_], V](error: Throwable, prev: V, retryAfter: Option[Instant]) extends State[F, V]

  private sealed trait Obligation[F[_], V]
  private case class Refresh[F[_], V](old: V, set: DeferredSink[F, F[V]]) extends Obligation[F, V]
  private case class Wait[F[_], V](get: DeferredSource[F, F[V]]) extends Obligation[F, V]
  private case class Return[F[_], V](value: F[V]) extends Obligation[F, V]

  sealed abstract class RefreshError(msg: String, cause: Throwable) extends Exception(msg, cause)
  case class WillRetryRefresh private[RefreshableCache](msg: String, cause: Throwable) extends RefreshError(msg, cause)
  case class WillNotRetryRefresh private[RefreshableCache](msg: String, cause: Throwable) extends RefreshError(msg, cause)
  case class CancelledRefresh private[RefreshableCache](msg: String) extends RefreshError(msg, Throwable())

  private class RefreshableCacheImpl[F[_]: {Concurrent as F, Clock as clk}, V](
    cache: Ref[F, State[F, V]],
    isValid: (Instant, V) => Boolean,
    refresh: V => F[V]
  ) extends RefreshableCache[F, V] {

    override val get: F[V] =
      for {
        dfr <- Deferred[F, F[V]]
        now <- clk.realTimeInstant
        ans <-
          F.uncancelable { poll =>
            cache
              .modify(computeTransition(dfr, now))
              .flatMap(fulfillObligation(poll))
          }
      } yield ans

    private def computeTransition(dfr: Deferred[F, F[V]], now: Instant): State[F, V] => (State[F, V], Obligation[F, V]) =
      case s@Valid(v) =>
        if isValid(now, v) then (s, Return(v.pure))
        else (Refreshing(dfr), Refresh(v, dfr))
      case s@Refreshing(get) => (s, Wait(get))
      case s@Failed(e, v, Some(ra)) =>
        if ra.isBefore(now) then (Refreshing(dfr), Refresh(v, dfr))
        else (s, Return(WillRetryRefresh("Refreshing cache failed, but will retry in some time.", e).raiseError))
      case s@Failed(e, v, None) =>
        (s, Return(WillNotRetryRefresh("Refreshing cache failed, will not try again.", e).raiseError))

    private def fulfillObligation(poll: Poll[F]): Obligation[F, V] => F[V] =
      case Return(v) => v
      case Wait(get) => poll(get.get.flatten)
      case Refresh(old, set) =>
        poll(refresh(old)).guaranteeCase {
          case Outcome.Succeeded(fa) =>
            for {
              a <- fa
              _ <- cache.set(Valid(a))
              _ <- set.complete(a.pure[F])
            } yield ()
          case Outcome.Errored(e) =>
            for {
              now <- clk.realTimeInstant
              _ <- cache.set(Failed(e, old, Some(now)))
              _ <- set.complete(e.raiseError)
            } yield ()
          case Outcome.Canceled() =>
            for {
              e = CancelledRefresh("The refresh was cancelled.")
              _ <- cache.set(Failed(e, old, None))
              _ <- set.complete(e.raiseError)
            } yield ()
        }
  }
}
