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
    Developer(
      "BartekBH",
      "Bartłomiej Homętowski",
      "bartek.hometowski@gmail.com",
      url("https://github.com/BartekBH"),
    ),
  ),
  versionScheme := Some("semver-spec"),
  Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
  Test / fork   := true, // required for tasty collector to work
)

lazy val root = (project in file("."))
  .settings(
    name           := "domainDocs4s",
    publish / skip := true,
  )
  .aggregate(core, examples)

lazy val core = (project in file("domainDocs4s-core"))
  .settings(commonSettings)
  .settings(
    name := "domainDocs4s-core",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.6.1",
    ),
  )

lazy val examples = (project in file("domainDocs4s-examples"))
  .settings(commonSettings)
  .settings(
    name              := "domainDocs4s-examples",
    libraryDependencies ++= Seq(
      "ch.epfl.scala" %% "tasty-query" % "1.4.0",
    ),
    semanticdbEnabled := true,
  )
  .dependsOn(core)

lazy val stableVersion = taskKey[String]("stableVersion")
stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}
