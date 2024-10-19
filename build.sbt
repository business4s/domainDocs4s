ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.5.1"

lazy val root = (project in file("."))
  .settings(
    name := "domainDocs4s-root",
  )
  .aggregate(api, collectorApi, tastyCollector, semanticdbCollector, javaAnnotationProcessorCollector, reflectionCollector, testInput)

lazy val api = (project in file("domainDocs4s-api"))
  .settings(
    name := "domainDocs4s-api",
  )

lazy val collectorApi = (project in file("domainDocs4s-collector-api"))
  .settings(
    name := "domainDocs4s-collector-api",
  )

lazy val tastyCollector = (project in file("domainDocs4s-collector-tasty"))
  .settings(
    name := "domainDocs4s-collector-tasty",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-tasty-inspector" % scalaVersion.value,
    ),
  )
  .dependsOn(api)
  .dependsOn(testInput % "test->compile")

lazy val tastyQueryCollector = (project in file("domainDocs4s-collector-tasty-query"))
  .settings(
    name := "domainDocs4s-collector-tasty-query",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.4.0",
    ),
  )
  .dependsOn(api)
  .dependsOn(testInput) //% "test->compile")

lazy val semanticdbCollector              = (project in file("domainDocs4s-collector-semanticdb"))
  .settings(
    name := "domainDocs4s-collector-semanticdb",
    libraryDependencies ++= Seq(
      ("org.scalameta" %% "scalameta" % "4.8.14").cross(CrossVersion.for3Use2_13),
    ),
  )
lazy val javaAnnotationProcessorCollector = (project in file("domainDocs4s-collector-java-annotation-processor"))
  .settings(
    name := "domainDocs4s-collector-java-annotation-processor",
    libraryDependencies ++= Seq(
      "javax.annotation" % "javax.annotation-api" % "1.3.2",
    ),
  )
  .dependsOn(testInput % "test->compile")
  .dependsOn(api)

lazy val reflectionCollector = (project in file("domainDocs4s-collector-reflection"))
  .settings(
    name := "domainDocs4s-collector-reflection",
  )
  .dependsOn(testInput) // % "test->compile"
  .dependsOn(api)

lazy val classgraphCollector = (project in file("domainDocs4s-collector-classgraph"))
  .settings(
    name := "domainDocs4s-collector-classgraph",
    libraryDependencies ++= Seq(
      "io.github.classgraph" % "classgraph" % "4.8.177",
    ),
  )
  .dependsOn(testInput) // % "test->compile"
  .dependsOn(api)

lazy val izumiCollector = (project in file("domainDocs4s-collector-coblocke-scala-reflection"))
  .settings(
    name                               := "domainDocs4s-collector-coblocke-scala-reflection",
    libraryDependencies += "co.blocke" %% "scala-reflection" % "2.0.8",
  )
  .dependsOn(testInput) // % "test->compile"
  .dependsOn(api)

lazy val testInput = (project in file("domainDocs4s-test-input"))
  .settings(
    name              := "domainDocs4s-test-input",
    semanticdbEnabled := true,
  )
  .dependsOn(api)
