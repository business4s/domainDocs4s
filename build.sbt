ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.5.1"

lazy val root = (project in file("."))
  .settings(
    name := "domainDocs4s-root",
  )
  .aggregate(api, tastyQueryCollector , testInput)

lazy val api = (project in file("domainDocs4s-api"))
  .settings(
    name := "domainDocs4s-api",
  )

lazy val tastyQueryCollector = (project in file("domainDocs4s-collector"))
  .settings(
    name := "domainDocs4s-collector",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.4.0",
    ),
  )
  .dependsOn(api)
  .dependsOn(testInput) //% "test->compile")

lazy val testInput = (project in file("domainDocs4s-test-input"))
  .settings(
    name              := "domainDocs4s-test-input",
    semanticdbEnabled := true,
  )
  .dependsOn(api)
