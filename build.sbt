
lazy val commonSettings = Seq(
  githubProject := "cedi-config",
  crossScalaVersions := Seq("2.13.1", "2.12.10", "2.13.5"),
  scalacOptions --= Seq("-Ywarn-unused-import", "-Xfuture"),
  scalacOptions ++= Seq("-language:higherKinds") ++ (CrossVersion.partialVersion(scalaBinaryVersion.value) match {
     case Some((2, v)) if v <= 12 => Seq("-Xfuture", "-Ywarn-unused-import", "-Ypartial-unification", "-Yno-adapted-args")
     case _ => Seq.empty
  }),
  scalacOptions in (Compile, console) ~= (_ filterNot Set("-Xfatal-warnings", "-Ywarn-unused-import").contains),
  contributors ++= Seq(
    Contributor("mpilquist", "Michael Pilquist")
  ),
  releaseCrossBuild := true
)

lazy val root = project.in(file(".")).aggregate(core).settings(commonSettings).settings(noPublish)

lazy val core = project.in(file("core")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "config",
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.4.0",
      "com.chuusai" %% "shapeless" % "2.3.3",
      "org.scalatest" %% "scalatest" % "3.1.1" % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.config")
  )

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  scalacOptions := Nil,
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core)
