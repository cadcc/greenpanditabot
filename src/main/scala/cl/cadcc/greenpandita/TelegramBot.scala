package cl.cadcc.greenpandita

import cats.*
import cats.data.OptionT
import cats.derived.*
import cats.effect.*
import cats.effect.std.{Console, MapRef, Random}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import cl.cadcc.greenpandita.GoogleForms.{FormId, PrettyResponse, RawForm}
import cl.cadcc.greenpandita.TelegramBot.JobData
import cl.cadcc.greenpandita.model.NotifyIntegration
import cl.cadcc.greenpandita.primitives.PollingWorker
import doobie.util.transactor.Transactor
import doobie.syntax.all.*
import iozhik.OpenEnum.Known
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory
import telegramium.bots.high.implicits.*
import telegramium.bots.high.{Api, BotApi, LongPollBot, Methods}
import telegramium.bots.*

import java.time.Instant
import scala.concurrent.duration.DurationInt

case class ChatId(id: Long, topicId: Option[Int])

class TelegramBot[
  F[_]: {
    Parallel,
    Async as F,
    Console,
    Transactor as xa,
    LoggerFactory,
    PollingWorker as pollingWorker},
](
  googleTokens: GoogleTokenService[F],
  googleForms: GoogleForms[F],
  api: Api[F],
  config: TelegramConfig,
  notifyConfig: NotifyIntegrationConfig,
) extends LongPollBot[F](api) {
  private given Api[F] = api
  private val logger = LoggerFactory[F].getLogger

  private val ownerId = config.ownerId
  private val authorizedGroup = ChatIntId(config.authorizedGroup)
  private val cron = notifyConfig.defaultFreq

  sealed abstract class CommandException(msg: String, cause: Throwable) extends Exception(msg, cause)
  case class MessageCommandException private[TelegramBot](msg: String, cause: Throwable = null) extends Exception(msg, cause)
  case class SilentCommandException  private[TelegramBot](msg: String, cause: Throwable = null) extends Exception(msg, cause)

  private case class MessageData(chatId: ChatId, isDm: Boolean, user: User, text: String, trackId: String) derives Show

  private def sendMessage(msg: MessageData, text: String): F[Unit] =
    sendMessage(msg.chatId.id, msg.chatId.topicId, text)

  private def sendMessage(chatId: Long, topicId: Option[Int], text: String) =
    Methods.sendMessage(ChatIntId(chatId), text, messageThreadId = topicId, parseMode = Markdown.some)
      .exec.void

  private def sendMessageRaw(msg: MessageData, text: String): F[Unit] =
    Methods.sendMessage(ChatIntId(msg.chatId.id), text, messageThreadId = msg.chatId.topicId)
      .exec.void

  override def onMessage(msg: Message): F[Unit] =
    val chat = msg.chat
    val isDm = chat.`type` == "private"
    val chatId = ChatId(chat.id, msg.messageThreadId)

    val data = for {
      user <- msg.from
      if !user.isBot
      text <- msg.text
      tackingId = s"msg-${msg.messageId}-${chatId.id}#${msg.date}"
    } yield MessageData(chatId, isDm, user, text, tackingId)

    data match
      case Some(v) =>
        val commandMain =
          for {
            _ <- logger.debug(s"[${v.trackId}] Received Telegram message.")
            isAuth <- authenticate(v)
            _ <- if isAuth then handleAuthenticatedMessage(v) else ().pure[F]
          } yield ()
        commandMain.recoverWith {
          case e @ MessageCommandException(msg, cause) =>
            logger.info(e)(s"[${v.trackId}] An expected exception happened") *>
            Methods.sendMessage(ChatIntId(v.chatId.id), msg, messageThreadId = v.chatId.topicId).exec.void
          case e @ SilentCommandException(msg, cause) =>
            logger.error(cause)(s"[${v.trackId}] Silent exception found while processing Telegram message.")
          case e =>
            logger.error(e)(s"[${v.trackId}] Unhandled exception found while processing message")
            *> sendMessage(v,
              s"""Ocurrió un error al intentar procesar el comando :(.
                |Contacta a Sistemas CaDCC con este identificador
                |```txt
                |${v.trackId}
                |```""".stripMargin)
        }
      case None => ().pure[F]

  private def handleAuthenticatedMessage(msg: MessageData): F[Unit] =
    if !msg.text.startsWith("/") then
      return ().pure[F]
    val fragments = msg.text.strip().substring(1).split(' ').filter(_.nonEmpty).toList

    fragments match {
      case List("connect", service) => handleConnectService(msg, service)
      case List("connect@greenpanditabot", service) => handleConnectService(msg, service)
      case "notify" :: service :: args => handleNotify(msg, service, args)
      case "notify@greenpanditabot" :: service :: args => handleNotify(msg, service, args)
      case _ => ().pure[F]
    }

  private def authenticate(msg: MessageData): F[Boolean] =
    Methods.getChatMember(authorizedGroup, msg.user.id)
      .exec
      .map {
        case Known(_ : ChatMemberMember | _ : ChatMemberAdministrator | _ : ChatMemberOwner) => true
        case _ => false
      }

  private val inputServiceList: Seq[String] = Seq("GoogleForms")
  private def handleConnectService(msg: MessageData, service: String): F[Unit] =
    MonadThrow[F].raiseWhen(!msg.isDm)(MessageCommandException("No puedes ocupar este comando en grupos, solo en DMs."))
    *> (service match
      case "GoogleForms" =>
        for {
          hasToken <- googleTokens.hasToken(msg.user.id)
          _ <- MonadThrow[F].raiseWhen(hasToken)(MessageCommandException("Ya haz autorizado este servicio."))
          uri <- googleTokens.getTokenAcquireUri(msg.user.id)
          _ <- sendMessageRaw(msg, s"Abre este link en el navegador para vincular tu cuenta de telegram con tu cuenta de Google: $uri")
        } yield ()
      case _ =>
        sendMessage(msg, s"No conozco ese servicio :(. Actualmente soporto [${inputServiceList.mkString(", ")}].")
    )
  
  private def handleNotify(msg: MessageData, service: String, args: List[String]): F[Unit] =
    (service, args) match
      case ("GoogleForms", List(id)) =>
        for {
          // TODO: verify the forms id is correct & have access
          b <- googleTokens.hasToken(msg.user.id)
          _ <- MonadThrow[F].raiseUnless(b)(MessageCommandException("Debes usar /connect para conectar tu cuenta de Google primero."))

          cp <-
            googleForms
              .getCheckpoint(googleTokens.getTokenOrError(msg.user.id), FormId(id))
              .adaptErr { th => MessageCommandException(
                s"""Algo salió mal al intentar crear la integración.
                   |Tal vez la id del Forms es incorrecta?
                   |Si el problema persiste, contactar a Sistemas CaDCC indicando el siguiente identificador:
                   |```txt
                   |${msg.trackId}
                   |```""".stripMargin, th) }
          _ <- Console[F].println(s"Computé este checkpoint: $cp")

          ni <- NotifyIntegration.save(
            ownerId = msg.user.id,
            chatId = msg.chatId.id,
            threadId = msg.chatId.topicId,
            cron = cron,
            formsId = id,
            checkpoint = cp
          ).transact(xa)

          job <- F.delay { googleFormsNotify(msg.user.id, msg.chatId.id, msg.chatId.topicId, id, ni.id, cp) }
          _ <- pollingWorker.runEvery(cron){ _ => job }
          _ <- sendMessage(msg, s"Integración (id := ${ni.id}) creada correctamente!\nDesde ahora recibirás notificaciones desde GoogleForms en este chat.")
        } yield ()
      case ("GoogleForms", _) => sendMessage(msg, "Te faltó incluir el id del Google Forms a conectar.")
      case _ =>
        sendMessage(msg, s"No conozco ese servicio :(. Actualmente soporto [${inputServiceList.mkString(", ")}].")

  case class EmptyNotify() extends Exception("No new messages.")
  private def googleFormsNotify(userId: Long, chatId: Long, topicId: Option[Int], formsId: String, notifyId: Int, checkpoint: Instant): F[Unit] =
    var cp = checkpoint
    val main =
      for {
        (form, responses, b) <- googleForms.listFormResponses(
          googleTokens.getTokenOrError(userId),
          GoogleForms.FormId(formsId),
          after = cp.some,
          limit = 20.some
        )
        _ <- F.raiseWhen(responses.isEmpty)(EmptyNotify())
        responsesSort = responses.sortWith((l, r) => l.submittedAt.isBefore(r.submittedAt))
        _ <- F.delay { cp = responsesSort.last.submittedAt }
        _ <- logger.debug(s"New checkpoit := $cp")
        _ <- NotifyIntegration.update(notifyId, cp).transact(xa)
        _ <- sendMessage(chatId, topicId, googleFormsNotifyMsg(form, responsesSort, b))
      } yield ()
    main.handleErrorWith {
      case EmptyNotify() =>
        logger.info(s"Notication integration (id := $notifyId) did not have new submissions.")
      case t =>
        logger.error(t)(s"Error occurred while polling a notify integration (id := $notifyId).")
          *> sendMessage(chatId, topicId, s"Algo salió mal cuando intenté procesar la notificación (id := $notifyId) :(. Contactar Sistemas CaDCC")
    }

  private def googleFormsNotifyMsg(form: RawForm, responses: List[PrettyResponse], overlimit: Boolean): String =
    val body = responses
      .mapWithIndex { (response, i) =>
        val answers =
          response.items
            .map(i =>
              val answers = i.answer.map(_.data).mkString("\n   + ")
              s" - ${i.title}\n   + $answers").mkString("\n")
        s"Respuesta N°$i:\n$answers"
      }.mkString("\n\n")
    val over = if overlimit then "+" else ""
    s"Alerta! hay ${responses.length}$over nuevas respuestas en el formulario ${form.info.getTitle}\n\n$body"

  val populate: F[Unit] =
    NotifyIntegration
      .listAll
      .transact(xa)
      .evalTap { ni =>
        for {
          job <- F.delay { googleFormsNotify(ni.ownerId, ni.chatId, ni.threadId, ni.formsId, ni.id, ni.checkpoint) }
          _ <- pollingWorker.runEvery(ni.cron) { _ => job }
          _ <- logger.debug(s"Started integration (id := ${ni.id}).")
          _ <- logger.debug(s"Job started with checkpoit := ${ni.checkpoint}")
        } yield ()
      }
      .compile
      .drain
}

object TelegramBot {
  case class JobData(ticket: Unique.Token, checkpoint: Instant, ownerId: Long)

  def make[F[_]: {Parallel, Async as F, Console, PollingWorker, Transactor, LoggerFactory}](
    client: Client[F],
    googleTokens: GoogleTokenService[F],
    googleForms: GoogleForms[F],
    config: GreenPanditaConfig
  ): Resource[F, Unit] =
    val uri = config.telegram.apiBaseUri / s"bot${config.telegram.token}"
    val api = BotApi[F](client, uri.toString)
    val bot = TelegramBot[F](googleTokens, googleForms, api, config.telegram, config.integration.notifications)
    for {
      _ <- bot.populate.toResource
      _ <- bot.start().background
    } yield ()
}
