package cl.cadcc.greenpandita.model

import java.time.Instant

case class GoogleRefreshToken(
  telegramId: Long,
  refreshToken: String,
  updatedAt: Instant
)
