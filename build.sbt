import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill",
  scalaVersion := "3.4.0-RC1-bin-20230825-2616c8b-NIGHTLY"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.7"
val logback = "ch.qos.logback" % "logback-classic" % "1.4.7"

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "concurrency-in-scala")
  .aggregate(core)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.ox" %% "core" % "0.0.12",
      "org.apache.pekko" %% "pekko-actor-typed" % "1.0.1",
      "org.apache.pekko" %% "pekko-stream" % "1.0.1",
      "dev.zio" %% "zio-streams" % "2.0.16",
      logback,
      scalaTest
    )
  )
