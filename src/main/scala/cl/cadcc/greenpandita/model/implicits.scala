package cl.cadcc.greenpandita.model

import cron4s.{Cron, CronExpr}
import doobie.util.{Get, Put}

private[model] object implicits {

  given Get[CronExpr] = Get[String].tmap(Cron.unsafeParse)
  given Put[CronExpr] = Put[String].tcontramap(_.toString)
}
