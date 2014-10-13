package xsbtWebStart

import scala.xml.Elem

import sbt._
import Keys.TaskStreams

import xsbtUtil.types._
import xsbtUtil.{ util => xu }
	
import xsbtClasspath.{ Asset => ClasspathAsset, ClasspathPlugin }
import xsbtClasspath.Import.classpathAssets

object Import {
	// TODO probably not a good idea
	
	case class GenConfig(
		dname:String,
		validity:Int
	)
	
	case class KeyConfig(
		keyStore:File,
		storePass:String,
		alias:String,
		keyPass:String
	)
	
	case class JnlpConfig(
		fileName:String, 
		descriptor:(String,Seq[JnlpAsset])=>Elem
	)
	
	case class JnlpAsset(href:String, main:Boolean, size:Long) {
		def toElem:Elem	= <jar href={href} main={main.toString} size={size.toString}/> 
	}
	
	//------------------------------------------------------------------------------
	
	val webstart			= taskKey[File]("complete build, returns the output directory")
	val webstartKeygen		= taskKey[Unit]("generate a signing key")
	
	val webstartGenConfig	= settingKey[Option[GenConfig]]("configurations for signing key generation")
	val webstartKeyConfig	= settingKey[Option[KeyConfig]]("configuration for signing keys")
	val webstartJnlpConfigs	= settingKey[Seq[JnlpConfig]]("configurations for jnlp files to create")
	val webstartManifest	= settingKey[Option[File]]("manifest file to be included in jar files")
	val webstartExtras		= taskKey[Traversable[PathMapping]]("extra files to include in the build")

	val webstartBuildDir	= settingKey[File]("where to put the output files")
}

object WebStartPlugin extends AutoPlugin {
	//------------------------------------------------------------------------------
	//## exports
	
	lazy val autoImport	= Import
	import autoImport._
	
	override val requires:Plugins		= ClasspathPlugin && plugins.JvmPlugin
	
	override val trigger:PluginTrigger	= noTrigger

	override lazy val projectSettings:Seq[Def.Setting[_]]	=
			Vector(
				webstart		:=
						buildTask(
							streams		= Keys.streams.value,
							assets		= classpathAssets.value,
							keyConfig	= webstartKeyConfig.value,
							jnlpConfigs	= webstartJnlpConfigs.value,
							manifest	= webstartManifest.value,
							extras		= webstartExtras.value,
							buildDir	= webstartBuildDir.value
						),
				webstartKeygen	:=
						keygenTask(
							streams		= Keys.streams.value,
							genConfig	= webstartGenConfig.value,
							keyConfig	= webstartKeyConfig.value
						),
						
				webstartGenConfig	:= None,
				webstartKeyConfig	:= None,
				webstartJnlpConfigs	:= Vector.empty,
				webstartManifest	:= None,
				webstartExtras		:= Vector.empty,
				
				webstartBuildDir	:= Keys.crossTarget.value / "webstart",
				
				Keys.watchSources	:= Keys.watchSources.value ++ webstartManifest.value.toVector
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		keyConfig:Option[KeyConfig],
		jnlpConfigs:Seq[JnlpConfig],
		manifest:Option[File],
		extras:Traversable[PathMapping],
		buildDir:File
	):File	= {
		// BETTER copy and sign fresh jars only unless they did not exist before
		val assetMap		= assets map { _.flatPathMapping } map (xu.pathMapping anchorTo buildDir)
		streams.log info "copying assets"
		// BETTER care about freshness
		val assetsToCopy	= assetMap filter { case (source, target) => source newerThan target }
		val assetsCopied	= IO copy assetsToCopy
		
		// BETTER care about freshness
		val freshJars	= assetsCopied
		if (freshJars.nonEmpty) {
			if (manifest.isEmpty) {
				streams.log info "missing manifest, leaving jar manifests unchanged"
			}
			manifest foreach { manifest =>
				streams.log info "extending jar manifests"
				freshJars.par foreach { jar =>
					extendManifest(manifest, jar, streams.log)
				}
			}
			
			if (keyConfig.isEmpty) {
				streams.log info "missing KeyConfig, leaving jar files unsigned"
			}
			keyConfig foreach { keyConfig =>
				streams.log info "signing jars"
				freshJars.par foreach { jar =>
					signAndVerify(keyConfig, jar, streams.log)
				}
			}
		}
		else {
			streams.log info "no fresh jars to sign"
		}
		
		// @see http://download.oracle.com/javase/tutorial/deployment/deploymentInDepth/jnlpFileSyntax.html
		streams.log info "creating jnlp descriptor(s)"
		// main jar must come first
		val sortedAssets	=
				assets sortBy { !_.main } map { cp:ClasspathAsset =>
					JnlpAsset(cp.name, cp.main, cp.jar.length)
				}
		// TODO util: zipBy
		val configFiles:Seq[(JnlpConfig,File)]	= jnlpConfigs map { it => (it, buildDir / it.fileName) }
		configFiles foreach { case (jnlpConfig, jnlpFile) =>
			val xml:Elem	= jnlpConfig descriptor (jnlpConfig.fileName, sortedAssets)
			val str:String	= """<?xml version="1.0" encoding="utf-8"?>""" + "\n" + xml
			IO write (jnlpFile, str)
		}
		val jnlpFiles	= configFiles map { _._2 }
		
		streams.log info "copying extras"
		val extrasToCopy	= extras map (xu.pathMapping anchorTo buildDir)
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info "cleaning up"
		val allFiles	= (buildDir * "*").get.toSet
		val jarFiles	= assetMap map xu.fileMapping.getTarget
		val obsolete	= allFiles -- jarFiles -- extrasCopied -- jnlpFiles 
		IO delete obsolete
		
		buildDir
	}
	
	private def extendManifest(manifest:File, jar:File, log:Logger) {
		val rc	=
				Process("jar", List(
					"umf",
					manifest.getAbsolutePath,
					jar.getAbsolutePath
				)) ! log
		if (rc != 0)	sys error s"manifest change failed: ${rc}"
	}
	
	private def signAndVerify(keyConfig:KeyConfig, jar:File, log:Logger) {
		// sigfile, storetype, provider, providerName
		val rc1	=
				Process("jarsigner", List(
					// "-verbose",
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storepass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					// TODO makes the vm crash???
					// "-signedjar",	jar.getAbsolutePath,
					jar.getAbsolutePath,
					keyConfig.alias
				)) ! log
		if (rc1 != 0)	sys error s"sign failed: ${rc1}"
	
		val rc2	= 
				Process("jarsigner", List(
					"-verify",
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storepass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					jar.getAbsolutePath
				)) ! log
		if (rc2 != 0)	sys error s"verify failed: ${rc2}"
	}
	
	//------------------------------------------------------------------------------
	
	private def keygenTask(
		streams:TaskStreams,
		genConfig:Option[GenConfig],
		keyConfig:Option[KeyConfig]
	) {
		if (genConfig.isEmpty)	xu.fail logging (streams, s"${webstartGenConfig.key.label} must be set")
		if (keyConfig.isEmpty)	xu.fail logging (streams, s"${webstartKeyConfig.key.label} must be set")
		for {
			genConfig	<- genConfig
			keyConfig	<- keyConfig
		} {
			streams.log info s"creating webstart key in ${keyConfig.keyStore}"
			genkey(keyConfig, genConfig, streams.log)
		}
	}
	
	private def genkey(keyConfig:KeyConfig, genConfig:GenConfig, log:Logger) {
		val rc	= 
				Process("keytool", List(
					"-genkey", 
					"-dname",		genConfig.dname, 
					"-validity",	genConfig.validity.toString, 
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storePass",	keyConfig.storePass, 
					"-keypass",		keyConfig.keyPass,
					"-alias",		keyConfig.alias
				)) ! log
		if (rc != 0)	sys error s"key gen failed: ${rc}"
	}
}
