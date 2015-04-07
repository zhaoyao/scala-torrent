import scalariform.formatter.preferences._

name := "storrent"

version := "1.0"

scalaVersion := "2.11.4"

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += Resolver.defaultLocal

libraryDependencies := Seq(
  //  "com.wacai" %% "config-annotation" % "0.1-SNAPSHOT",
  "com.github.zhaoyao" %% "sbencoding" % "0.1.0-SNAPSHOT",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3",
  "io.spray" %% "spray-client" % "1.3.2",
  "io.spray" %% "spray-routing" % "1.3.2",
  "io.spray" %% "spray-json" % "1.3.1",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.8",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
  "com.typesafe.akka" %% "akka-actor" % "2.3.8",
  "ch.qos.logback" % "logback-classic" % "1.0.13",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.8" % "test"
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Ywarn-dead-code",
  "-feature",
//  "-Ylog-classpath",
  "-language:_",
  "-target:jvm-1.7",
  "-encoding", "UTF-8"
)

scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignParameters, true)
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DoubleIndentClassDeclaration, true)
  .setPreference(PreserveDanglingCloseParenthesis, true)
  .setPreference(SpacesWithinPatternBinders, true)

lazy val storrent = project.in(file("."))
