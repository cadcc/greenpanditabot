package cl.cadcc.greenpandita

import cats.effect.Sync
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import cron4s.{Cron, CronExpr}
import org.http4s.Uri
import pureconfig.error.CannotConvert
import pureconfig.module.catseffect.syntax.*
import pureconfig.module.ip4s.given
import pureconfig.module.http4s.given
import pureconfig.{ConfigObjectSource, ConfigReader, ConfigSource}

private given ConfigReader[CronExpr] = ConfigReader[String].emap(s =>
  Cron(s) match
    case Left(value) => CannotConvert(s, classOf[CronExpr].getCanonicalName, value.getMessage).asLeft
    case Right(value) => value.asRight
)

case class NotifyIntegrationConfig(
  defaultFreq: CronExpr
) derives ConfigReader

case class GoogleConfig(
  clientId: String,
  clientSecret: String
) derives ConfigReader

case class IntegrationConfig(
  notifications: NotifyIntegrationConfig,
  google: GoogleConfig
) derives ConfigReader

case class HttpConfig(
  host: Host,
  port: Port,
  baseUri: Uri
) derives ConfigReader

case class TelegramConfig(
  authorizedGroup: Long,
  ownerId: Option[Long],
  token: String,
  apiBaseUri: Uri
) derives ConfigReader

case class DatabaseConfig(
  driver: String,
  connectionString: String,
  username: String,
  password: String
) derives ConfigReader

case class GreenPanditaConfig(
  http: HttpConfig,
  telegram: TelegramConfig,
  integration: IntegrationConfig,
  db: DatabaseConfig
) derives ConfigReader

object GreenPanditaConfig {

  def load[F[_]: Sync](base: ConfigObjectSource): F[GreenPanditaConfig] =
    base
      .withFallback(ConfigSource.default)
      .loadF[F, GreenPanditaConfig]()

  def load[F[_]: Sync](configFile: Option[String]): F[GreenPanditaConfig] =
    val baseSource = configFile match
      case Some(value) => ConfigSource.file(value)
      case None => ConfigSource.empty
    load(baseSource)
}
