package cl.cadcc.greenpandita

import cats.{MonadThrow, Show}
import cats.data.OptionT
import cats.derived.*
import cats.effect.*
import cats.effect.syntax.all.*
import cats.effect.std.{Console, Dequeue, Dispatcher, MapRef, Queue, Random, SecureRandom}
import cats.syntax.all.*
import cl.cadcc.greenpandita.primitives.{Refreshable, RefreshableCache}
import io.circe.Codec
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.http4s.{HttpRoutes, Uri}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.given
import doobie.syntax.all.*
import doobie.postgres.implicits.given

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.{Duration, DurationInt}
import scala.math.Ordering.Implicits.infixOrderingOps

trait GoogleTokenService[F[_]] {
  def getToken(id: Long): F[Option[String]]
  def getTokenOrError(id: Long): F[String]
  def getTokenAcquireUri(id: Long): F[Uri]
  def hasToken(id: Long): F[Boolean]

  val callback: HttpRoutes[F]
}

object GoogleTokenService {

  def apply[F[_]: {Sync, Concurrent as F, Console, Transactor, PollingWorker as poll}](
    client: Client[F],
    config: GreenPanditaConfig
  ): F[GoogleTokenService[F]] =
    given Concurrent[F] = F
    val redirectUri = config.http.baseUri / "google-oauth" / "callback"
    for {
      tokens <- MapRef[F, Long, RefreshableCache[F, GoogleTokens]]
      requests <- MapRef[F, String, TokenRequest]
      queue <- Dequeue.unbounded[F, String]
      rand <- SecureRandom.of
      service = GoogleTokenServiceImpl(
        client,
        tokens,
        requests,
        queue,
        rand,
        config.integration.google,
        redirectUri.show,
        logging.getLoggerFromClass(classOf[GoogleTokenServiceImpl])
      )
      _ <- poll.runEvery(1.hour)(_ => service.cleanup)
      _ <- service.populate // TODO: maybe should this on demand. This could hit the rate limit.
    } yield service
  
  private case class GoogleTokens(session: String, expiresAt: Instant, refresh: String)
  private case class TokenRequest(id: Long, expiresAt: Instant)
  private case class DbTokens(id: Long, refresh: String, updatedAt: Instant)

  private class GoogleTokenServiceImpl[F[_] : {Clock, Concurrent as F, Console, Transactor as xa}](
    client: Client[F],
    tokenCache: MapRef[F, Long, Option[RefreshableCache[F, GoogleTokens]]],
    requestsData: MapRef[F, String, Option[TokenRequest]],
    requests: Dequeue[F, String],
    rand: SecureRandom[F],
    config: GoogleConfig,
    val redirectUri: String,
    logger: Logger[F]
  ) extends GoogleTokenService[F] {
    private given Random[F] = rand

    private val googleAuthBase : Uri = uri"https://accounts.google.com/o/oauth2/v2/auth"
    private val googleTokensBase: Uri = uri"https://oauth2.googleapis.com/token"

    private val clientId: String = config.clientId
    private val clientSecret: String = config.clientSecret

    private val requestExpirity: Duration = 10.minutes

    override def getToken(id: Long): F[Option[String]] =
      OptionT(tokenCache(id).get).semiflatMap(_.get).map(_.session).value

    override def getTokenOrError(id: Long): F[String] =
      OptionT(getToken(id)).getOrRaise(new NoSuchElementException("No token found... may have been revoked."))

    private def buildAcquireUri(state: String) =
      googleAuthBase
        +? ("client_id", clientId)
        +? ("redirect_uri", redirectUri)
        +? ("state", state)
        +? ("response_type", "code")
        +? ("scope", "https://www.googleapis.com/auth/forms.responses.readonly https://www.googleapis.com/auth/forms.body.readonly")
        +? ("access_type", "offline")

    private val encoder = Base64.getEncoder
    override def getTokenAcquireUri(id: Long): F[Uri] =
      for {
        rand <- Random[F].nextBytes(24).map(encoder.encode).map(arr => String(arr, StandardCharsets.UTF_8))
        now <- Clock[F].realTimeInstant
        nonce = id.show + "-" + rand
        request = TokenRequest(id, now.plusMillis(requestExpirity.toMillis))
        _ <- F.uncancelable {_ =>
          requestsData(nonce).update(_ => Some(request))
          *> requests.offerBack(nonce)
        }
      } yield buildAcquireUri(nonce)

    override def hasToken(id: Long): F[Boolean] =
      tokenCache(id).get.map(_.isDefined)

    override val callback: HttpRoutes[F] = ServerImpl.callback

    private[GoogleTokenService] val cleanup: F[Unit] =
      val loop: F[Boolean] =
        F.uncancelable { poll =>
          requests.tryTakeFront.flatMap {
            case Some(req) =>
              val handle = requestsData(req)
              handle.get.flatMap {
                case Some(data) => // This case means that the request has not been fulfilled. May require cleanup
                  for {
                    now <- Clock[F].realTimeInstant
                    g = data.expiresAt.isBefore(now)
                    _ <-
                      if g then handle.set(None) // Request expired, do cleanup
                      else requests.offerFront(req) // Request valid. Cleanup done.
                  } yield g
                case None => true.pure // This case means that the request was fulfilled. No cleanup needed.
              }
            case None => false.pure // All requests cleared. Nothing to do.
          }
        }
      F.iterateWhile(loop)(identity).void

    private[GoogleTokenService] val populate: F[Unit] =
      sql"""SELECT * FROM google_refresh_tokens"""
        .query[DbTokens]
        .stream
        .transact(xa)
        .foreach { dbTokens =>
          for {
            tokens <- Client.refreshToken(dbTokens.refresh)
            cache <- makeCache(tokens)
            _ <- tokenCache(dbTokens.id).set(Some(cache))
          } yield ()
        }
        .compile
        .drain

    private def validateState(state: String): F[Long] =
      for {
        req <- OptionT(requestsData(state).get).getOrRaise(RuntimeException("Invalid state filed. Callback forgery?"))
        now <- Clock[F].realTimeInstant
        _ <- requestsData(state).set(None)
        _ <- MonadThrow[F].raiseWhen(req.expiresAt <= now)(RuntimeException("Service connection request has expired. Retry."))
      } yield req.id

    private given Refreshable[F, GoogleTokens] {
      override def isValid(now: Instant, token: GoogleTokens): Boolean =
        now.isBefore(token.expiresAt)
      override def refresh(old: GoogleTokens): F[GoogleTokens] = Client.refreshToken(old.refresh)
    }

    private def makeCache(init: GoogleTokens): F[RefreshableCache[F, GoogleTokens]] =
      RefreshableCache[F, GoogleTokens](init)

    private def loadTokens(id: Long, tokens: GoogleTokens): F[Unit] =
      for {
        cache <- makeCache(tokens)
        _ <- F.uncancelable { poll =>
          poll(persistTokens(id, tokens))
          *> tokenCache(id).set(Some(cache))
        }
      } yield ()

    private def persistTokens(id: Long, tokens: GoogleTokens): F[Unit] =
      for {
        now <- Clock[ConnectionIO].realTimeInstant
        count <-
          sql"""INSERT INTO google_refresh_tokens(telegram_id, refresh_token, updated_at)
                | VALUES ($id, ${tokens.refresh}, $now)
                | ON CONFLICT (telegram_id)
                | DO UPDATE SET
                |   refresh_token = ${tokens.refresh},
                |   updated_at = $now""".update.run

      } yield ()
      sql"INSERT INTO google_refresh_tokens(telegram_id, refresh_token) VALUES ($id, ${tokens.refresh})"
        .update.run.transact(xa).void

    private object ServerImpl {
      import org.http4s.dsl.Http4sDsl
      private val http4sDls = Http4sDsl[F]
      import http4sDls.*

      private object AuthCodeQueryMatcher extends QueryParamDecoderMatcher[String]("code")
      private object AuthScopeQueryMatcher extends QueryParamDecoderMatcher[String]("scope")
      private object AuthStateQueryMatcher extends QueryParamDecoderMatcher[String]("state")
      private object AuthIssuerQueryMater extends QueryParamDecoderMatcher[String]("iss")

      val callback: HttpRoutes[F] =
        HttpRoutes.of[F] {
          case req@GET -> Root / "google-oauth" / "callback"
            :? AuthCodeQueryMatcher(code)
            +& AuthScopeQueryMatcher(scope)
            +& AuthStateQueryMatcher(state)
            +& AuthIssuerQueryMater(iss) =>

            val scopes = scope.split(' ')
            for {
              id <- validateState(state)
              tokens <- Client.acquireTokens(code)
              _ <- loadTokens(id, tokens)
              _ <- Console[F].println(tokens.session)
              ans <- Ok("Connection successful")
            } yield ans
        }
    }

    private object Client {
      import org.http4s.client.dsl.Http4sClientDsl
      private val http4sDsl = Http4sClientDsl[F]
      import http4sDsl.*

      private case class AcquireTokensRequest(
        client_id: String,
        client_secret: String,
        grant_type: String,
        redirect_uri: Option[String] = None,
        code: Option[String] = None,
        refresh_token: Option[String] = None,
      ) derives Codec

      private case class AcquireTokensResponse(
        access_token: String,
        expires_in: Long,
        refresh_token: Option[String],
        refresh_token_expires_in: Option[Long],
        scope: String,
        token_type: String
      ) derives Codec

      def acquireTokens(code: String): F[GoogleTokens] =
        val body = AcquireTokensRequest(
          client_id = clientId,
          client_secret = clientSecret,
          code = Some(code),
          grant_type = "authorization_code",
          redirect_uri = Some(redirectUri)
        )
        val req = POST(googleTokensBase).withEntity(body)
        for {
          now <- Clock[F].realTimeInstant
          res <- client.expect[AcquireTokensResponse](req)
          _ <- MonadThrow[F].raiseUnless(res.token_type == "Bearer")(RuntimeException("Google granted unknown token type. Notify administrators."))
          refresh <- OptionT.fromOption(res.refresh_token).getOrRaise(RuntimeException("Did not get refresh token. Implementation mistake?"))
          // TODO: check that we got the scopes we requested
        } yield GoogleTokens(res.access_token, now.plusSeconds(res.expires_in), refresh)

      def refreshToken(refreshToken: String): F[GoogleTokens] =
        val body = AcquireTokensRequest(
          client_id = clientId,
          client_secret = clientSecret,
          grant_type = "refresh_token",
          refresh_token = Some(refreshToken)
        )
        val req = POST(googleTokensBase).withEntity(body)
        for {
          _ <- logger.debug("Refreshing an Access Token")
          now <- Clock[F].realTimeInstant
          res <- client.expect[AcquireTokensResponse](req)
          _ <- MonadThrow[F].raiseUnless(res.token_type == "Bearer")(RuntimeException("Google granted unknown token type. Notify administrators."))
        } yield GoogleTokens(res.access_token, now.plusSeconds(res.expires_in), refreshToken)
    }
  }
}
