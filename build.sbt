Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / versionScheme := Some("early-semver")

sbtPlugin		:= true

name			:= "xsbt-webstart"
organization	:= "de.djini"
version			:= "2.11.0-SNAPSHOT"

scalacOptions	++= Seq(
	"-feature",
	"-deprecation",
	"-unchecked",
	"-Xfatal-warnings",
)

addSbtPlugin("de.djini" % "xsbt-util"		% "1.6.0")
addSbtPlugin("de.djini" % "xsbt-classpath"	% "2.8.0")
