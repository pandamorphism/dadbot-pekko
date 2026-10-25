ThisBuild / scalaVersion := "3.3.9"
ThisBuild / organization := "dadbot"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

val PekkoVersion = "1.7.1"
val LogbackVersion = "1.5.38"
val ScalaTestVersion = "3.2.19"

lazy val root = (project in file("."))
  .aggregate(common, part1Actors)
  // `run` isn't aggregated, so root needs the parts on its classpath for Zed's ▶ (`sbt run` / `runMain`).
  .dependsOn(part1Actors)
  .settings(
    name := "dadbot",
    publish / skip := true,
    run / fork := true,
    Compile / discoveredMainClasses ++= (part1Actors / Compile / discoveredMainClasses).value
  )

// Services shared by every part: pun generator, chat, the scripted conversation.
lazy val common = project
  .settings(
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % "2.0.17",
      "ch.qos.logback" % "logback-classic" % LogbackVersion
    )
  )

lazy val part1Actors = Project("part1-actors", file("part1-actors"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion % Test,
      "org.scalatest" %% "scalatest" % ScalaTestVersion % Test
    ),
    run / fork := true,
    Test / fork := true
  )
