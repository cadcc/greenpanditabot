package cl.cadcc.greenpandita

import cats.{ApplicativeError, ApplicativeThrow, MonadThrow}
import cats.data.{EitherT, OptionT}
import cats.effect.{Concurrent, Sync}
import cats.effect.std.MapRef
import cats.syntax.all.*
import cl.cadcc.greenpandita.GoogleForms.{FormId, FormsJson, PrettyResponse, RawForm, RawResponse}
import fs2.{Chunk, Pull, RaiseThrowable, Stream}
import io.circe.generic.auto.deriveDecoder
import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.syntax.*
import io.circe.fs2.decoder as fs2Decoder
import org.http4s.Credentials.Token
import org.http4s.Method.GET
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.Authorization
import org.http4s.syntax.literals.*
import org.http4s.{AuthScheme, Credentials, Headers, Method, Request, Uri}
import org.typelevel.log4cats.{Logger, LoggerFactory}

import java.time.Instant
import scala.List
import scala.collection.{Map, immutable, mutable}

trait GoogleForms[F[_]] {
  type Checkpoint = Instant

  case class RawResponseResult(responses: Stream[F, RawResponse], k: Option[F[RawResponseResult]])

  def listRawResponses(tokenSource: F[String], id: FormId, after: Option[Instant] = None, limit: Option[Int] = None): F[RawResponseResult]
  def listFormResponses(tokenSource: F[String], id: FormId, after: Option[Instant] = None, limit: Option[Int] = None): F[(RawForm, List[PrettyResponse], Boolean)]
  def getForms(tokenSource: F[String], id: FormId): F[RawForm]
  def getCheckpoint(tokenSource: F[String], id: FormId): F[Checkpoint]
}

object GoogleForms {

  def apply[F[_]: {Sync, Concurrent, LoggerFactory as logging}](client: Client[F]): GoogleForms[F] =
    given Logger[F] = logging.getLogger
    GoogleFormsImpl[F](client)

  case class FormId(id: String)
  case class Answer(questionId: String, data: String)
  case class RawResponse(id: String, answers: List[Answer], submittedAt: Instant)
  case class PrettyResponse(id: String, items: List[Section], submittedAt: Instant)
  case class Section(id: String, title: String, answer: List[Answer])
  type RawForm = FormsJson.Form

  private class GoogleFormsImpl[F[_]: {Concurrent as F, Logger as logger}](client: Client[F]) extends GoogleForms[F] {
    private val http4sClientDls = Http4sClientDsl[F]
    private val formResponsesJson = FormResponsesJson[F]
    import http4sClientDls.*

    private val formsBaseUri: Uri = uri"https://forms.googleapis.com/v1/forms"

    private def listUri(id: FormId, after: Option[Instant] = None, limit: Option[Int] = None, pageToken: Option[String] = None): Uri =
      val queryParams: immutable.Map[String, String] = immutable.Map[String, Option[String]](
        ("filter", after.map(v => s"timestamp > $v")),
        ("pageSize", limit.map( _.show )),
        ("pageToken", pageToken),
      ).collect {
          case (k, Some(value)) => (k, value)
      }

      val path = formsBaseUri / s"${id.id}" / "responses"
      path.withQueryParams(queryParams)

    override def listRawResponses(tokenSource: F[String], id: FormId, after: Option[Instant], limit: Option[Int]): F[RawResponseResult] =
      val uri = listUri(id, after = after, limit = limit)
      listRawResponsesUri(uri, id, tokenSource)(listRawResponsesPaginated(tokenSource, id, limit))

    private def listRawResponsesPaginated(tokenSource: F[String], id: FormId, limit: Option[Int] = None)(nextPage: String): F[RawResponseResult] =
      val uri = listUri(id, pageToken = nextPage.some, limit = limit)
      listRawResponsesUri(uri, id, tokenSource)(listRawResponsesPaginated(tokenSource, id, limit))

    private def listRawResponsesUri(uri: Uri, id: FormId, tokenSource: F[String])(k: String => F[RawResponseResult]): F[RawResponseResult] =
      for {
        token <- tokenSource
        req = Request[F](
          method = Method.GET,
          uri = uri,
          headers = Headers(Authorization(Token(AuthScheme.Bearer, token)))
        )
        res <-
          client.expect[formResponsesJson.Responses](req)
            .map { responses =>
              val parsed = parseResponsesResult(responses)
              responses.nextPageToken match
                case Some(token) => RawResponseResult(parsed, k(token).some)
                case None => RawResponseResult(parsed, None)
            }
        _ <- logger.trace(s"Obtained responses for Google Forms [$id] := [$res]")
      } yield res

    private def parseResponsesResult(responses: formResponsesJson.Responses): Stream[F, RawResponse] =
      responses.responses.map { resp =>
        val answers =
          resp
            .answers
            .values
            .map { ans =>
              val texts = ans.textAnswers.getOrElse(formResponsesJson.TextAnswers(Vector.empty))
              Answer(ans.questionId, texts.answers.mkString("\n"))
            }
        RawResponse(resp.responseId, answers.toList, resp.lastSubmittedTime)
      }

    override def listFormResponses(tokenSource: F[String], id: FormId, after: Option[Instant], limit: Option[Int]): F[(RawForm, List[PrettyResponse], Boolean)] =
      for {
        form <- getForms(tokenSource, id)
        responsesRes <- listRawResponses(tokenSource, id, after, limit)
        formIndex = GoogleFormsImpl.prepareForm(form)
        prettyResponsesResults <- responsesRes.responses.compile.toList
        prettyResponsesRes = prettyResponsesResults.traverse { res => GoogleFormsImpl.prettifyResponse(formIndex, res) }
        b = responsesRes.k.isDefined
        prettyResponses <- EitherT.fromEither(prettyResponsesRes).rethrowT
      } yield (form, prettyResponses, b)
    
    override def getForms(tokenSource: F[String], id: FormId): F[RawForm] =
      for {
        token <- tokenSource
        req =
          GET(formsBaseUri / id.id)
            .withHeaders(Headers(Authorization(Token(AuthScheme.Bearer, token))))
        form <- client.expect[FormsJson.Form](req)
        _ <- logger.trace(s"Obtained data for GoogleForms [$id] := [$form]")
      } yield form

    override def getCheckpoint(tokenSource: F[String], id: FormId): F[Checkpoint] =
      allResponses(tokenSource, id)
        .map(_.submittedAt)
        .fold(Instant.MIN)((bsf, submittedAt) => if bsf.isBefore(submittedAt) then submittedAt else bsf)
        .compile
        .onlyOrError

    private def allResponses(tokenSource: F[String], id: FormId): Stream[F, RawResponse] =
      Pull.loop[F, RawResponse, F[RawResponseResult]] { frrr =>
        Stream.eval(frrr).pull.headOrError.flatMap { rrr =>
          rrr.responses.pull.echo.map( _ => rrr.k)
        }
      }(listRawResponses(tokenSource, id)).stream
  }

  private[GoogleForms] object GoogleFormsImpl {

    sealed abstract class PrettifyError(msg: String) extends Exception(msg)
    case class UnknownQuestion(questionId: String) extends PrettifyError(s"The question with id [$questionId] referenced in the answer is unknown.")
    case class ImplementationError(message: String) extends PrettifyError(message)

    type PrettifyResult = Either[PrettifyError, PrettyResponse]

    case class FormIndex(itemsQuestions: List[(FormsJson.FormsItem, List[FormsJson.Question])])
    def prepareForm(forms: RawForm): FormIndex =
      val itemsQuestions = forms.items.collect {
        case item @ FormsJson.QuestionItem(itemId, title, description, questionItem) =>
          (item, List(questionItem.question))
        case item @ FormsJson.QuestionGroupItem(itemId, title, description, questionGroupItem) =>
          (item, questionGroupItem.questions)
      }
      FormIndex(itemsQuestions)

    private def buildAnswersIndex(responses: RawResponse): Map[String, Answer] =
      responses.answers.map { answer => (answer.questionId, answer) }.toMap

    def prettifyResponse(index: FormIndex, response: RawResponse): PrettifyResult =
      val answerIndex = buildAnswersIndex(response)
      index.itemsQuestions
        .traverse { (item, questions) =>
          questions.traverse { question =>
            answerIndex.get(question.questionId).toRight(UnknownQuestion(question.questionId))
          }.map((item, _))
        }.map { itemAnswers =>
          val sections = itemAnswers.map { (item, answers) => Section(item.itemId_, item.title_, answers) }
          PrettyResponse(response.id, sections, response.submittedAt)
        }
  }

  object FormsJson {
    case class Form(
      formId: String,
      info: FormsInfo,
      items: List[FormsItem]
    ) derives Codec

    case class FormsInfo(
      title: Option[String],
      documentTitle: Option[String],
      description: Option[String]
    ) derives Codec {
      def getTitle: String = title.orElse(documentTitle).getOrElse("<!Sin Título!>")
    }

    sealed trait FormsItem(val itemId_ : String, val title_ : String, val description_ : Option[String])

    given Encoder[FormsItem] = Encoder.instance[FormsItem] {
      case x: QuestionItem => x.asJson
      case x: QuestionGroupItem => x.asJson
      case x: PageBreakItem => x.asJson
      case x: TextItem => x.asJson
      case x: ImageItem => x.asJson
      case x: VideoItem => x.asJson
    }

    given Decoder[FormsItem] =
      List[Decoder[FormsItem]](
        Decoder[QuestionItem].widen,
        Decoder[QuestionGroupItem].widen,
        Decoder[PageBreakItem].widen,
        Decoder[TextItem].widen,
        Decoder[ImageItem].widen,
        Decoder[VideoItem].widen
      ).reduceLeft(_ or _)

    case class QuestionItem(
      itemId: String,
      title: String,
      description: Option[String],
      questionItem: QuestionItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class QuestionItemItem(question: Question) derives Codec
    case class Question(questionId: String) derives Codec

    case class QuestionGroupItem(
      itemId: String,
      title: String,
      description: Option[String],
      questionGroupItem: QuestionGroupItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class QuestionGroupItemItem(
      questions: List[Question]
    ) derives Codec

    case class PageBreakItem(
      itemId: String,
      title: String,
      description: Option[String],
      pageBreakItem: PageBreakItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class PageBreakItemItem() derives Codec

    case class TextItem(
      itemId: String,
      title: String,
      description: Option[String],
      textItem: TextItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class TextItemItem() derives Codec

    case class ImageItem(
      itemId: String,
      title: String,
      description: Option[String],
      imageItem: ImageItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class ImageItemItem() derives Codec

    case class VideoItem(
      itemId: String,
      title: String,
      description: Option[String],
      videoItem: VideoItemItem
    ) extends FormsItem(itemId, title, description) derives Codec

    case class VideoItemItem() derives Codec
  }


  private class FormResponsesJson[F[_]: ApplicativeThrow as F] {
    given Decoder[Stream[F, Response]] =
      Decoder[Vector[Json]].map(vec => Stream.emits(vec).through(fs2Decoder))

    given Decoder[Responses] = Decoder.instance { cursor =>
      val responsesCursor = cursor.downField("responses")
      if responsesCursor.failed then Responses(Stream.empty, None).asRight
      else
        for {
          responses <- cursor.get[Stream[F, Response]]("responses")
          nextPageToken <- cursor.get[Option[String]]("nextPageToken")
        } yield Responses(responses, nextPageToken)
    }

    case class Responses(responses: Stream[F, Response], nextPageToken: Option[String])

    case class Response(
      responseId: String,
      createTime: Instant,
      answers: Map[String, Answer],
      lastSubmittedTime: Instant
    ) derives Codec

    case class Answer(
      questionId: String,
      textAnswers: Option[TextAnswers]
    ) derives Codec

    case class TextAnswers(
      answers: Vector[TextAnswer]
    ) derives Codec

    case class TextAnswer(
      value: String
    ) derives Codec
  }
}
