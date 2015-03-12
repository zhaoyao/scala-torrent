name := "storrent"

version := "1.0"

scalaVersion := "2.11.4"

resolvers += Resolver.sonatypeRepo("snapshot")

libraryDependencies := Seq(
  //  "com.wacai" %% "config-annotation" % "0.1-SNAPSHOT",
  "com.github.mauricio" %% "mysql-async" % "0.2.16",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3",
  "io.spray" %% "spray-client" % "1.3.2",
  "io.spray" %% "spray-routing" % "1.3.2",
  "io.spray" %% "spray-caching" % "1.3.2",
  "io.spray" %% "spray-json" % "1.3.1",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.8",
  "com.typesafe.akka" %% "akka-actor" % "2.3.8",
  "ch.qos.logback" % "logback-classic" % "1.0.13",
  "com.sksamuel.elastic4s" %% "elastic4s" % "1.4.12",
  "org.jsoup" % "jsoup" % "1.8.1"
)
//
//scalacOptions ++= Seq(
//  "-unchecked",
//  "-deprecation",
//  "-Xlint",
//  "-Ywarn-dead-code",
////  "-Ylog-classpath",
//  "-language:_",
//  "-target:jvm-1.7",
//  "-encoding", "UTF-8"
//)

lazy val storrent = project.in(file("."))
