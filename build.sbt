name := "streaming-ocr"

version := "0.1"

scalaVersion := "2.12.8"

libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "4.3.1"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.12"
libraryDependencies += "com.typesafe.akka" %% "akka-http"   % "10.1.1"
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.1"