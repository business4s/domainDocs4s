import org.typelevel.scalacoptions.ScalacOptions

lazy val commonSettings = Seq(
  scalaVersion  := "3.8.2",
  scalacOptions ++= Seq("-no-indent"),
  tpolecatExcludeOptions += ScalacOptions.fatalWarnings, // deprecated in 3.8.2 (now -Werror)
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
  run / fork    := true,
)

lazy val root = (project in file("."))
  .settings(
    name           := "domainDocs4s",
    publish / skip := true,
  )
  .aggregate(core, examples, viewerCy, viewerCyE2E)

lazy val core = (project in file("domainDocs4s-core"))
  .settings(commonSettings)
  .settings(
    name := "domainDocs4s-core",
    libraryDependencies ++= Seq(
      "ch.epfl.scala"          %% "tasty-query" % "1.7.0",
      "com.github.jsqlparser"   % "jsqlparser"  % "5.3",
    ),
  )

lazy val examples = (project in file("domainDocs4s-examples"))
  .enablePlugins(Fs2Grpc)
  .settings(commonSettings)
  .settings(
    name              := "domainDocs4s-examples",
    libraryDependencies ++= Seq(
      "org.tpolecat"          %% "doobie-core"                   % "1.0.0-RC6",
      "com.typesafe.slick"    %% "slick"                         % "3.6.1",
      "org.apache.pekko"      %% "pekko-persistence-typed"       % "1.4.0",
      "org.apache.pekko"      %% "pekko-persistence-query"       % "1.4.0",
      "org.apache.pekko"      %% "pekko-projection-eventsourced" % "1.1.0",
      "org.apache.pekko"      %% "pekko-connectors-kafka"        % "1.1.0",
      "software.amazon.awssdk" % "s3"                            % "2.30.2",
      "com.github.fd4s"       %% "fs2-kafka"                     % "3.6.0",
    ),
    semanticdbEnabled := true,
    // suppress warnings from protobuf/scalapb generated code
    scalacOptions ++= Seq(
      "-Wconf:src=target/scala-.*:s",
    ),
  )
  .dependsOn(core)

lazy val viewerCy = (project in file("domainDocs4s-viewer-cy"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name         := "domainDocs4s-viewer-cy",
    scalaVersion := "3.8.2",
    scalacOptions ++= Seq("-no-indent"),
    tpolecatExcludeOptions += ScalacOptions.fatalWarnings,
    organization := "org.business4s",
    publish / skip := true,
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(
          org.scalajs.linker.interface.ModuleSplitStyle.SmallModulesFor(List("domaindocs4s.viewercy")),
        )
    },
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "com.raquo"    %%% "laminar"     % "17.2.0",
    ),
  )

lazy val viewerCyE2E = (project in file("domainDocs4s-viewer-cy-e2e"))
  .settings(
    name           := "domainDocs4s-viewer-cy-e2e",
    scalaVersion   := "3.8.2",
    scalacOptions ++= Seq("-no-indent"),
    tpolecatExcludeOptions += ScalacOptions.fatalWarnings,
    organization   := "org.business4s",
    publish / skip := true,
    Test / fork    := true,
    Test / javaOptions ++= Seq(
      s"-Dviewer.dir=${(viewerCy / baseDirectory).value.getAbsolutePath}",
    ),
    libraryDependencies ++= Seq(
      "org.scalatest"           %% "scalatest"        % "3.2.19" % Test,
      "org.seleniumhq.selenium"  % "selenium-java"    % "4.27.0" % Test,
      "io.github.bonigarcia"     % "webdrivermanager" % "5.9.2"  % Test,
    ),
  )

lazy val stableVersion = taskKey[String]("stableVersion")
stableVersion := {
  if (isVersionStable.value && !isSnapshot.value) version.value
  else previousStableVersion.value.getOrElse("unreleased")
}
