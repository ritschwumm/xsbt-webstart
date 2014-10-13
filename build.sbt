sbtPlugin		:= true

name			:= "xsbt-webstart"

organization	:= "de.djini"

version			:= "1.1.0"

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
	"-feature"
)

addSbtPlugin("de.djini" % "xsbt-util"		% "0.1.0")

addSbtPlugin("de.djini" % "xsbt-classpath"	% "1.1.0")
