sbtPlugin		:= true

name			:= "xsbt-webstart"
organization	:= "de.djini"
version			:= "2.2.0"

scalacOptions	++= Seq(
	"-deprecation",
	"-unchecked",
	// "-language:implicitConversions",
	// "-language:existentials",
	// "-language:higherKinds",
	// "-language:reflectiveCalls",
	// "-language:dynamics",
	// "-language:postfixOps",
	// "-language:experimental.macros"
	"-feature",
	"-Xfatal-warnings"
)

conflictManager	:= ConflictManager.strict
addSbtPlugin("de.djini" % "xsbt-util"		% "1.1.0")
addSbtPlugin("de.djini" % "xsbt-classpath"	% "2.1.0")
