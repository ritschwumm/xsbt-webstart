sbtPlugin		:= true

name			:= "xsbt-webstart"

organization	:= "de.djini"

version			:= "0.10.0"

scalacOptions	++= Seq("-deprecation", "-unchecked")

libraryDependencies <+= (sbtBinaryVersion in update, scalaVersion) { (sbtV, scalaV) =>
	Defaults.sbtPluginExtra("de.djini" % "xsbt-classpath" % "0.4.0", sbtV, scalaV) % "compile"
}
