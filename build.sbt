ThisBuild / scalaVersion         := "3.2.1"
ThisBuild / version              := "2.0.0"
ThisBuild / organization         := "io.github.ollls"
ThisBuild / organizationName     := "ollls"
ThisBuild / versionScheme        := Some("strict")
ThisBuild / organizationHomepage := Some(url("https://github.com/ollls/zio-tls-http"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ollls/zio-tls-http"),
    "scm:git@github.com:ollls/zio-tls-http.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "ostrygun",
    name = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url = url("https://github.com/ollls/")
  )
)

ThisBuild / description := "Scala ZIO/zio-json Web Server with native TLS"
ThisBuild / licenses    := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage    := Some(url("https://github.com/ollls/zio-tls-http"))
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
ThisBuild / credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "F85809244447DB9FA35A3C9B1EB44A5FC60F4104", // key identifier
  "ignored"                                   // this field is ignored; passwords are supplied by pinentry
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

lazy val root = (project in file("."))
  .settings(
    name                                   := "zio-tls-http",
    maxErrors                              := 3,
    retrieveManaged                        := true,
    libraryDependencies += "dev.zio"       %% "zio"               % "2.0.8" % "provided",
    libraryDependencies += "dev.zio"       %% "zio-test"          % "2.0.8",
    libraryDependencies += "dev.zio"       %% "zio-json"          % "0.4.2",
    libraryDependencies += "dev.zio"       %% "zio-logging-slf4j" % "2.1.5",
    libraryDependencies += "org.slf4j"      % "slf4j-api"         % "2.0.4",
    libraryDependencies += "ch.qos.logback" % "logback-classic"   % "1.3.5"
  )

lazy val example = (project in file("examples/start"))
  .settings(
    organization := "com.ols",
    name         := "zio-tls-http-exampe",
    version      := "0.0.1",
    maxErrors    := 3,
    libraryDependencies ++= Seq(
        "dev.zio" %% "zio" % "2.0.8"
    ),
    testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )
  .dependsOn(root)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-no-indent"
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("chk", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
