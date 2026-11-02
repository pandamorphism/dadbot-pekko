ThisBuild / scalaVersion := "3.3.9"
ThisBuild / organization := "dadbot"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

val PekkoVersion = "1.7.1"
val PekkoHttpVersion = "1.4.2"
val PekkoManagementVersion = "1.2.1"
val PekkoR2dbcVersion = "1.2.0"
val LogbackVersion = "1.5.38"
val ScalaTestVersion = "3.2.19"

lazy val root = (project in file("."))
  .aggregate(common, part1Actors, part2Messaging, part3Delivery)
  // `run` isn't aggregated, so root needs the parts on its classpath for Zed's ▶ (`sbt run` / `runMain`).
  .dependsOn(part1Actors, part2Messaging, part3Delivery)
  .settings(
    name := "dadbot",
    publish / skip := true,
    run / fork := true,
    Compile / discoveredMainClasses ++= (part1Actors / Compile / discoveredMainClasses).value,
    Compile / discoveredMainClasses ++= (part2Messaging / Compile / discoveredMainClasses).value,
    Compile / discoveredMainClasses ++= (part3Delivery / Compile / discoveredMainClasses).value
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

lazy val part2Messaging = Project("part2-messaging", file("part2-messaging"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
      // Pekko refuses to start with mixed module versions, and Management is built against an older Pekko,
      // so pin the modules it pulls in that nothing above does.
      "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
      "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
      "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test,
      "org.scalatest" %% "scalatest" % ScalaTestVersion % Test
    ),
    Compile / run / mainClass := Some("dadbot.part2.part2"),
    run / fork := true,
    Test / fork := true
  )

lazy val part3Delivery = Project("part3-delivery", file("part3-delivery"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % PekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % PekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % PekkoR2dbcVersion,
      // The plugin leaves the driver to the application. This is the version it was built and tested with.
      "org.postgresql" % "r2dbc-postgresql" % "1.0.7.RELEASE",
      // Built against an older Pekko, like HTTP and Management: pin what it pulls in.
      "org.apache.pekko" %% "pekko-persistence-query" % PekkoVersion,
      "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
      "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
      "org.apache.pekko" %% "pekko-management" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % PekkoManagementVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % PekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test,
      "org.scalatest" %% "scalatest" % ScalaTestVersion % Test
    ),
    Compile / run / mainClass := Some("dadbot.part3.part3"),
    run / fork := true,
    Test / fork := true
  )
