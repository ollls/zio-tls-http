val ZioVersion    = "1.0.3"
val Specs2Version = "4.7.0"

resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

lazy val root = (project in file("."))
  .settings(
    organization := "ZIO",
    name := "zio-tls-http",
    version := "0.0.1",
    scalaVersion := "2.13.1",
    maxErrors := 3,
    retrieveManaged := true,
    libraryDependencies ++= Seq(
      "dev.zio"    %% "zio"         % ZioVersion,
      "dev.zio" %% "zio-json" % "0.0.1",
      "dev.zio" %% "zio-test" % ZioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % ZioVersion % Test
    ),
     testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )

// Refine scalac params from tpolecat
scalacOptions ++= Seq(
  "-Wunused:imports",
  "-Xfatal-warnings",
  "-deprecation", 
)

//addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("chk", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
