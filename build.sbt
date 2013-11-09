sbtPlugin		:= true

name			:= "xsbt-webstart"

organization	:= "de.djini"

version			:= "0.13.0"

addSbtPlugin("de.djini" % "xsbt-classpath" % "0.6.0")

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
