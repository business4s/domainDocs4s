import org.typelevel.scalacoptions.ScalacOptions

lazy val commonSettings = Seq(
  scalaVersion  := "3.7.3",
  scalacOptions ++= Seq("-no-indent"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  ),
  organization  := "org.business4s",
  homepage      := Some(url("https://business4s.github.io/domainDocs4s/")),
  licenses      := List(License.MIT),
  developers    := List(
    Developer(
      "Krever",
      "Voytek Pituła",
      "w.pitula@gmail.com",
      url("https://v.pitula.me"),
    ),
  ),
  versionScheme := Some("semver-spec"),
  Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
)

lazy val root = (project in file("."))
  .settings(
    name := "domainDocs4s",
  )
  .aggregate(api, tastyQueryCollector , examples)

lazy val api = (project in file("domainDocs4s-api"))
  .settings(commonSettings)
  .settings(
    name := "domainDocs4s-api",
  )

lazy val tastyQueryCollector = (project in file("domainDocs4s-collector"))
  .settings(commonSettings)
  .settings(
    name := "domainDocs4s-collector",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.6.1",
    ),
  )
  .dependsOn(api)

lazy val examples = (project in file("domainDocs4s-examples"))
  .settings(commonSettings)
  .settings(
    name              := "domainDocs4s-examples",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.4.0",
    ),
    semanticdbEnabled := true,
  )
  .dependsOn(api)
  .dependsOn(tastyQueryCollector)

lazy val stableVersion = taskKey[String]("stableVersion")
stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}