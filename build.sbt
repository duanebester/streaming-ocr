name := "streaming-ocr"
version := "0.1"

val javacppVersion = "1.5"
version      := javacppVersion
scalaVersion := "2.12.8"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xlint")

// Platform classifier for native library dependencies
val platform = org.bytedeco.javacpp.Loader.getPlatform
// Libraries with native dependencies
val bytedecoPresetLibs = Seq(
  "opencv" -> s"4.0.1-$javacppVersion",
  "ffmpeg" -> s"4.1.3-$javacppVersion").flatMap {
  case (lib, ver) => Seq(
    // Add both: dependency and its native binaries for the current `platform`
    "org.bytedeco" % lib % ver withSources() withJavadoc(),
    "org.bytedeco" % lib % ver classifier platform
  )
}

libraryDependencies ++= bytedecoPresetLibs

libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "4.3.1"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.12"
libraryDependencies += "com.typesafe.akka" %% "akka-http"   % "10.1.1"
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.1"
libraryDependencies += "com.atlascopco" % "hunspell-bridj" % "1.0.4"
libraryDependencies += "org.apache.opennlp" % "opennlp-tools" % "1.9.1"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += Resolver.mavenLocal

autoCompilerPlugins := true

// add a JVM option to use when forking a JVM for 'run'
javaOptions += "-Xmx1G"
