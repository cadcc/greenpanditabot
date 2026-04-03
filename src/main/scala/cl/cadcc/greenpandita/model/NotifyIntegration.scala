package cl.cadcc.greenpandita.model

import cats.effect.Clock
import cl.cadcc.greenpandita.model
import cron4s.CronExpr
import doobie.enumerated.JdbcType
import doobie.{ConnectionIO, Meta}
import doobie.syntax.all.*
import doobie.postgres.implicits.given
import doobie.util.meta.MetaConstructors.Basic
import doobie.util.{Read, Write}
import implicits.given
import fs2.Stream

import java.time.Instant

case class NotifyIntegration(
  id: Int,
  ownerId: Long,
  chatId: Long,
  threadId: Option[Int],
  cron: CronExpr,
  formsId: String,
  checkpoint: Instant,
  createdAt: Instant,
  updatedAt: Instant
) derives Write, Read

object NotifyIntegration {
//  private given Meta[Instant] = Basic.oneObject(
//    JdbcType.Time,
//    Some("timestamp"),
//    classOf[Instant]
//  )

  def get(id: Int): ConnectionIO[NotifyIntegration] =
    sql"""SELECT * FROM notify_integrations WHERE id = $id""".query[NotifyIntegration].unique

  def save(
    ownerId: Long,
    chatId: Long,
    threadId: Option[Int],
    cron: CronExpr,
    formsId: String,
    checkpoint: Instant,
  ): ConnectionIO[NotifyIntegration] =
    sql"""INSERT INTO
        | notify_integrations(owner_id, chat_id, topic_id, cron, forms_id, checkpoint)
        | VALUES ($ownerId, $chatId, $threadId, $cron, $formsId, $checkpoint)"""
      .stripMargin
      .update
      .withUniqueGeneratedKeys[NotifyIntegration](
        "id", "owner_id", "chat_id", "topic_id", "cron",
        "forms_id", "checkpoint", "created_at", "updated_at")

  def update(id: Int, checkpoint: Instant): ConnectionIO[Boolean] =
    for {
      now <- Clock[ConnectionIO].realTimeInstant
      count <- sql"""UPDATE notify_integrations SET checkpoint = $checkpoint, updated_at = $now WHERE id = $id"""
        .update
        .run
    } yield count == 1

  val listAll: Stream[ConnectionIO, NotifyIntegration] =
    sql"""SELECT * FROM notify_integrations""".query[NotifyIntegration].stream
}
