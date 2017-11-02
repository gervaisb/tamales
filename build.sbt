scalaVersion := "2.12.2"

enablePlugins(JavaAppPackaging)
enablePlugins(WindowsPlugin)

lazy val root = (project in file("."))
  .settings(
    name := "Tamales",

    libraryDependencies += "com.evernote" % "evernote-api" % "1.25.1",
    libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "3.8.0",
    libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.2",
    libraryDependencies += "com.typesafe.play" %% "play-json" % "2.6.0-M1"
  )
  .settings(
    packageSummary := "Tamales",
    maintainer := "Bliase Gervais <gervais.b@gmail.com>",
    packageDescription := s"""Tamales, tasks synchronization. Windows MSI."""
  )
