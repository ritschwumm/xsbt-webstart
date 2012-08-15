sbtPlugin		:= true

name			:= "xsbt-webstart"

organization	:= "de.djini"

version			:= "0.6.0"

scalaVersion	:= "2.9.1"

scalacOptions	++= Seq("-deprecation", "-unchecked")

// addSbtPlugin("de.djini" % "xsbt-classpath" % "0.0.1")

libraryDependencies <+= (sbtVersion in update, scalaVersion) { (sbtV, scalaV) =>
	Defaults.sbtPluginExtra("de.djini" % "xsbt-classpath" % "0.1.0", sbtV, scalaV) % "compile"
}
