import sbt._

object PluginDef extends Build {
    override lazy val projects = Seq(root)
    lazy val root = Project("plugins", file(".")) dependsOn( classPathPlugin )
    lazy val classPathPlugin = uri("git://github.com/ritschwumm/xsbt-classpath.git")
}
