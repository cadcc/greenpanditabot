package cl.cadcc.greenpandita

import cats.effect.kernel.Resource.ExitCase.{Canceled, Errored}
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.comcast.ip4s.{host, port}
import com.zaxxer.hikari.HikariConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.log.LogHandler
import org.http4s.{HttpApp, client}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jFactory
import telegramium.bots.high.BotApi

given logging: Slf4jFactory[IO] = Slf4jFactory.create[IO]

object Main extends IOApp {

  private val logger: SelfAwareStructuredLogger[IO] = logging.getLogger

  private val token = "8634343428:AAG3ZSZQC0yr27ASEGTUwaJ6DMe3VMgkE0g"

  private def middleware(app: HttpApp[IO]): HttpApp[IO] = HttpApp[IO] { req =>
    for {
      res <- app(req).onError {
        case e => IO.println(e.toString)
      }
    } yield res
  }

  private def makeHttpServer(googleTokenService: GoogleTokenService[IO])(using config: GreenPanditaConfig): Resource[IO, Server] =
    val httpApp = googleTokenService.callback.orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(config.http.host)
      .withPort(config.http.port)
      .withHttpApp(middleware(httpApp))
      .build
      .onFinalize(IO.println("Http server finished."))

  private def makeTransactor(config: GreenPanditaConfig): Resource[IO, Transactor[IO]] =
    val hcpConfig = HikariConfig()
    hcpConfig.setDriverClassName(config.db.driver)
    hcpConfig.setJdbcUrl(config.db.connectionString)
    hcpConfig.setUsername(config.db.username)
    hcpConfig.setPassword(config.db.password)
    HikariTransactor
      .fromHikariConfig[IO](hcpConfig)
      .evalMap { xa =>
        for {
          b <- logger.isTraceEnabled
          newXa =
            if b then
              xa.withLogHandler(LogHandler.jdkLogHandler)
            else xa
        } yield newXa
      }

  private def makeResources: Resource[IO, (Server, Unit)] =
    (for {
      configFile <- IO.systemPropertiesForIO.get("greenpandita.config").toResource
      _ <- logger.info(s"Loading configuration from ${configFile.getOrElse("[[defaults]]")}").toResource
      config <- GreenPanditaConfig.load[IO](configFile).toResource
      _ <- logger.trace(s"Configuration loaded: $config").toResource
      xa <- makeTransactor(config)
      pw <- PollingWorker[IO]
    } yield (xa, config, pw))
      .flatMap { (xa, config, pw) =>
          given GreenPanditaConfig = config
          given Transactor[IO] = xa
          given PollingWorker[IO] = pw

          for {
            client <- EmberClientBuilder.default[IO].build
            gtServ <- GoogleTokenService[IO](client, config).toResource
            gfServ = GoogleForms[IO](client)
            server <- makeHttpServer(gtServ)
            bot <-
              TelegramBot
                .make[IO](client, gtServ, gfServ, config)
                .onFinalize(IO.println("Telegram bot finished."))
          } yield (server, bot)
      }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.println("I'm GreenPanditaBot, what is my purpose?")
      _ <-
        makeResources
          .use(_ => IO.println("I send notifications, from now on.") *> IO.never)
          .onCancel(IO.println("Goodbye."))
    } yield ExitCode.Success
}
