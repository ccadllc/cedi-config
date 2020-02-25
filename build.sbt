
lazy val commonSettings = Seq(
  githubProject := "cedi-config",
  contributors ++= Seq(
    Contributor("mpilquist", "Michael Pilquist")
  )
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
