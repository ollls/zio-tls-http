val Specs2Version = "4.7.0"

resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

//ThisBuild / organization := "io.github.ollls"
ThisBuild / organizationName := "ollls"
ThisBuild / organizationHomepage := Some(url("https://github.com/ollls/zio-tls-http"))

ThisBuild / scmInfo := Some(
ScmInfo(
    url("https://github.com/ollls/zio-tls-http"),
    "scm:git@github.com:ollls/zio-tls-http.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id    = "ostrygun",
    name  = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url   = url("https://github.com/ollls/")
  )
)

ThisBuild / description := "Scala ZIO/zio-json Web Server with native TLS"
ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/ollls/zio-tls-http"))


// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true



  lazy val root = (project in file("."))
  .settings(
    organization := "io.github.ollls",
    name := "zio-tls-http",
    version := "2.0.0.b",
    scalaVersion := "3.2.1",
    maxErrors := 3,
    retrieveManaged := true,
    libraryDependencies += "dev.zio" %% "zio"         % "2.0.5",
    libraryDependencies += "dev.zio" %% "zio-test"    % "2.0.5",
    libraryDependencies += "dev.zio" %% "zio-json"    % "0.3.0-RC6",
   
    testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )

  lazy val example = (project in file("examples/start")).settings(
    organization := "com.ols",
    name := "zio-tls-http-exampe",
    version := "0.0.1",
    scalaVersion := "3.2.1",
    maxErrors := 3,
    libraryDependencies ++= Seq(
    ),
    testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")) ).dependsOn( root )

  

// lazy val distro = (project in file("build"))

// Refine scalac params from tpolecat
scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-deprecation", 
)

//addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("chk", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
