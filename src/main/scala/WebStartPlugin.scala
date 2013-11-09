import sbt._

import scala.xml.Elem

import Keys.{ Classpath, TaskStreams, watchSources }
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin._

object WebStartPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## configuration objects
	
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
	//## exported
	
	val webstartKeygen			= TaskKey[Unit]("webstart-keygen")
	val webstartBuild			= TaskKey[File]("webstart")
	val webstartOutput			= SettingKey[File]("webstart-output")
	val webstartGenConfig		= SettingKey[Option[GenConfig]]("webstart-gen-configs")
	val webstartKeyConfig		= SettingKey[Option[KeyConfig]]("webstart-key-configs")                   
	val webstartJnlpConfigs		= SettingKey[Seq[JnlpConfig]]("webstart-jnlp-configs")
	val webstartManifest		= SettingKey[Option[File]]("webstart-manifest")
	val webstartExtras			= TaskKey[Traversable[(File,String)]]("webstart-extras")
		
	// webstartJnlp		<<= (Keys.name) { it => it + ".jnlp" },
	lazy val webstartSettings:Seq[Def.Setting[_]]	=
			classpathSettings ++ 
			Seq(
				webstartKeygen			<<= keygenTask,
				webstartBuild			<<= buildTask,
				webstartOutput			<<= (Keys.crossTarget) { _ / "webstart" },
				webstartGenConfig		:= None,
				webstartKeyConfig		:= None,
				webstartJnlpConfigs		:= Seq.empty,
				webstartManifest		:= None,
				webstartExtras			:= Seq.empty,
				watchSources			<<= (watchSources, webstartManifest) map { 
					(watchSources, webstartManifest) => {
						watchSources ++ webstartManifest.toSeq
					}
				}
			)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Def.Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,
		webstartKeyConfig,
		webstartJnlpConfigs,
		webstartManifest,
		webstartExtras,
		webstartOutput
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		keyConfig:Option[KeyConfig],
		jnlpConfigs:Seq[JnlpConfig],
		manifest:Option[File],
		extras:Traversable[(File,String)],
		output:File
	):File	= {
		// require(jnlpConf	!= null, s"${webstartJnlpConf.key.label} must be set")
		// BETTER copy and sign fresh jars only unless they did not exist before
		val assetMap	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= output / asset.name
				}
				yield (source, target)
				
		streams.log info "copying assets"
		// BETTER care about freshness
		val assetsToCopy	= assetMap filter { case (source,target) => source newerThan target }
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
		val sortedAssets	= assets sortBy { !_.main } map { cp:ClasspathAsset =>
			JnlpAsset(cp.name, cp.main, cp.jar.length)
		}
		val configFiles:Seq[(JnlpConfig,File)]	= jnlpConfigs map { it => (it, output / it.fileName) }
		configFiles foreach { case (jnlpConfig, jnlpFile) =>
			val xml:Elem	= jnlpConfig descriptor (jnlpConfig.fileName, sortedAssets)
			val str:String	= """<?xml version="1.0" encoding="utf-8"?>""" + "\n" + xml
			IO write (jnlpFile, str)
		}
		val jnlpFiles	= configFiles map { _._2 }
		
		streams.log info "copying extras"
		val extrasToCopy	= extras map { case (file,path) => (file, output / path) }
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info "cleaning up"
		val allFiles	= (output * "*").get.toSet
		val jarFiles	= assetMap map { case (source,target) => target }
		val obsolete	= allFiles -- jarFiles -- extrasCopied -- jnlpFiles 
		IO delete obsolete
		
		output
	}
	
	private def extendManifest(manifest:File, jar:File, log:Logger) {
		val rc	= Process("jar", List(
			"umf",
			manifest.getAbsolutePath,
			jar.getAbsolutePath
		)) ! log
		if (rc != 0)	sys error s"manifest change failed: ${rc}"
	}
	
	private def signAndVerify(keyConfig:KeyConfig, jar:File, log:Logger) {
		// sigfile, storetype, provider, providerName
		val rc1	= Process("jarsigner", List(
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
	
		val rc2	= Process("jarsigner", List(
			"-verify",
			"-keystore",	keyConfig.keyStore.getAbsolutePath,
			"-storepass",	keyConfig.storePass,
			"-keypass",		keyConfig.keyPass,
			jar.getAbsolutePath
		)) ! log
		if (rc2 != 0)	sys error s"verify failed: ${rc2}"
	}
	
	//------------------------------------------------------------------------------
	
	private def keygenTask:Def.Initialize[Task[Unit]] = (
		Keys.streams,
		webstartGenConfig,
		webstartKeyConfig
	) map keygenTaskImpl
			
	private def keygenTaskImpl(
		streams:TaskStreams,
		genConfig:Option[GenConfig],
		keyConfig:Option[KeyConfig]
	) {
		require(genConfig.nonEmpty, s"${webstartGenConfig.key.label} must be set")
		require(keyConfig.nonEmpty, s"${webstartKeyConfig.key.label} must be set")
		for {
			genConfig	<- genConfig
			keyConfig	<- keyConfig
		} {
			streams.log info s"creating webstart key in ${keyConfig.keyStore}"
			genkey(keyConfig, genConfig, streams.log)
		}
	}
	
	private def genkey(keyConfig:KeyConfig, genConfig:GenConfig, log:Logger) {
		val rc	= Process("keytool", List(
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
