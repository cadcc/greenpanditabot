ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.2"

val catsVersion = "3.7.0"
val http4sVersion = "0.23.33"
val telegramiumVersion = "10.905.0"
val doobieVersion = "1.0.0-RC12"
val circeVersion = "0.14.15"
val cron4sVersion = "0.8.2"
val pureconfigVersion = "0.17.10"
val log4catsVersion = "2.8.0"

lazy val root = (project in file("."))
  .settings(
    name := "GreenPanditaBot",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "kittens" % "3.5.0",

      "com.github.alonsodomin.cron4s" %% "cron4s-core" % cron4sVersion,

      // logging
      "org.typelevel" %% "log4cats-core" % log4catsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.32",

      // config
      "com.github.pureconfig" %% "pureconfig-core" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-cats-effect" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-http4s" % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-ip4s" % pureconfigVersion,

      // circe
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-fs2" % "0.14.1",

      "org.typelevel" %% "cats-effect" % catsVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "io.github.apimorphism" %% "telegramium-core" % telegramiumVersion,
      "io.github.apimorphism" %% "telegramium-high" % telegramiumVersion,

      // db
      "org.tpolecat" %% "doobie-core"     % doobieVersion,
      "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "com.zaxxer" % "HikariCP" % "7.0.2",
      "org.postgresql" % "postgresql" % "42.7.10",

      // test

    ),
    assembly / assemblyJarName := "greenPanditaBot.jar",
    assembly / mainClass := Some("cl.cadcc.greenpandita.Main")
  )
