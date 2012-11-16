import sbt._

import scala.xml.Elem

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

import ClasspathPlugin.{ classpathSettings, classpathAssets, Asset => ClasspathAsset }

object WebStartPlugin extends Plugin {
	//------------------------------------------------------------------------------
	//## configuration objects
	
	case class GenConf(
		dname:String,
		validity:Int
	)
	
	case class KeyConf(
		keyStore:File,
		storePass:String,
		alias:String,
		keyPass:String
	)
	
	case class JnlpConf(
		fileName:String, 
		descriptor:(String,Seq[JnlpAsset])=>Elem
	)
	
	case class JnlpAsset(href:String, main:Boolean, size:Long) {
		def toElem:Elem	= <jar href={href} main={main.toString} size={size.toString}/> 
	}
	
	private def mkAsset(cp:ClasspathAsset):JnlpAsset	= 
			JnlpAsset(cp.name, cp.main, cp.jar.length)
	
	/*
	case class JnlpConf(
		mainClass:String,
		fileName:String,
		codeBase:String,
		title:String,
		vendor:String,
		description:String,
		iconName:Option[String],
		splashName:Option[String],
		offlineAllowed:Boolean,
		// NOTE if this is true, signing is mandatory
		allPermissions:Boolean,
		j2seVersion:String,
		maxHeapSize:Int,
		extensions:Seq[ExtensionConf]		= Seq.empty,
		architectures:Seq[ArchitectureConf]	= Seq.empty
	)
	
	case class ExtensionConf(
		name:String,
		href:String,
		version:Option[String]
	)
	
	case class ArchitectureConf(
		os:String,
		arch:String,
		items:Seq[SpecificConf]
	)
	
	sealed trait SpecificConf
	case class PropertyConf(name:String, value:String)	extends SpecificConf
	case class NativeLibConf(href:String)				extends SpecificConf
	*/
	
	//------------------------------------------------------------------------------
	//## exported
	
	val webstartKeygen			= TaskKey[Unit]("webstart-keygen")
	val webstartBuild			= TaskKey[File]("webstart")
	val webstartOutputDirectory	= SettingKey[File]("webstart-output-directory")
	val webstartResources		= SettingKey[PathFinder]("webstart-resources")
	val webstartGenConf			= SettingKey[GenConf]("webstart-gen-conf")
	val webstartKeyConf			= SettingKey[KeyConf]("webstart-key-conf")                   
	val webstartJnlpConf		= SettingKey[Seq[JnlpConf]]("webstart-jnlp-conf")
	val webstartExtraFiles		= TaskKey[Seq[File]]("webstart-extra-files")
		
	// webstartJnlp		<<= (Keys.name) { it => it + ".jnlp" },
	lazy val webstartSettings	= classpathSettings ++ Seq(
		webstartKeygen			<<= keygenTask,
		webstartBuild			<<= buildTask,
		webstartOutputDirectory	<<= (Keys.crossTarget) { _ / "webstart" },
		webstartResources		<<= (Keys.sourceDirectory in Runtime) { _ / "webstart" },
		webstartGenConf			:= null,	// TODO ugly
		webstartKeyConf			:= null,	// TODO ugly
		webstartJnlpConf		:= Seq.empty,
		webstartExtraFiles		:= Seq.empty
	)
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Initialize[Task[File]] = (
		Keys.streams,
		classpathAssets,
		webstartKeyConf,
		webstartJnlpConf,
		webstartResources,
		webstartExtraFiles,
		webstartOutputDirectory
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[ClasspathAsset],
		keyConf:KeyConf,
		jnlpConfs:Seq[JnlpConf],
		webstartResources:PathFinder,
		extraFiles:Seq[File],
		outputDirectory:File
	):File	= {
		// require(jnlpConf	!= null, webstartJnlpConf.key.label		+ " must be set")
		// TODO copy and sign fresh jars only unless they did not exist before
		val assetMap	=
				for {
					asset	<- assets
					source	= asset.jar
					target	= outputDirectory / asset.name
				}
				yield (source, target)
				
		streams.log info ("copying assets")
		// TODO should care about freshness
		val assetsToCopy	= assetMap filter { case (source,target) => source newerThan target }
		val assetsCopied	= IO copy assetsToCopy
		
		// TODO should care about freshness
		val freshJars	= assetsCopied
		if (keyConf != null && freshJars.nonEmpty) {
			streams.log info ("signing jars")
			freshJars.par foreach { jar =>
				signAndVerify(keyConf, jar, streams.log)
			}
		}
		else if (keyConf == null) {
			streams.log info ("missing KeyConf, leaving jar files unsigned")
		}
		else {
			streams.log info ("no fresh jars to sign")
		}
		
		// @see http://download.oracle.com/javase/tutorial/deployment/deploymentInDepth/jnlpFileSyntax.html
		streams.log info ("creating jnlp descriptor(s)")
		// main jar must come first
		val sortedAssets	= assets sortBy { !_.main } 
		val confFiles:Seq[(JnlpConf,File)]	= jnlpConfs map { it => (it, outputDirectory / it.fileName) }
		confFiles foreach { case (jnlpConf, jnlpFile) =>
			val xml:Elem	= jnlpConf descriptor (jnlpConf.fileName, sortedAssets map mkAsset)
			val str:String	= """<?xml version="1.0" encoding="utf-8"?>""" + "\n" + xml
			IO write (jnlpFile, str)
		}
		val jnlpFiles	= confFiles map { _._2 }
		
		// TODO check
		// Keys.defaultExcludes
		streams.log info ("copying resources")
		val resourcesToCopy	=
				for {
					dir		<- webstartResources.get
					file	<- dir.***.get
					target	= Path rebase (dir, outputDirectory) apply file get
				}
				yield (file, target)
		val resourcesCopied	= IO copy resourcesToCopy
	
		streams.log info ("copying extra files")
		val extrasToCopy	= extraFiles map { it => (it, outputDirectory / it.getName) }
		val extrasCopied	= IO copy extrasToCopy
		
		streams.log info ("cleaning up")
		val allFiles	= (outputDirectory * "*").get.toSet
		val jarFiles	= assetMap map { case (source,target) => target }
		val obsolete	= allFiles -- jarFiles -- resourcesCopied -- extrasCopied -- jnlpFiles 
		IO delete obsolete
		
		outputDirectory
	}
	
	private def signAndVerify(keyConf:KeyConf, jar:File, log:Logger) {
		// sigfile, storetype, provider, providerName
		val rc1	= Process("jarsigner", List(
			// "-verbose",
			"-keystore",	keyConf.keyStore.getAbsolutePath,
			"-storepass",	keyConf.storePass,
			"-keypass",		keyConf.keyPass,
			// TODO makes the vm crash???
			// "-signedjar",	jar.getAbsolutePath,
			jar.getAbsolutePath,
			keyConf.alias
		)) ! log
		if (rc1 != 0)	sys error ("sign failed: " + rc1)
	
		val rc2	= Process("jarsigner", List(
			"-verify",
			"-keystore",	keyConf.keyStore.getAbsolutePath,
			"-storepass",	keyConf.storePass,
			"-keypass",		keyConf.keyPass,
			jar.getAbsolutePath
		)) ! log
		if (rc2 != 0)	sys error ("verify failed: " + rc2)
	}
	
	/*
	// main Assets come first in the Seq
	private def writeJnlp(jnlpConf:JnlpConf, assets:Seq[Asset], targetFile:File) {
		import jnlpConf._
		val xml	= 
				"""<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
				<jnlp spec="1.5+" codebase={codeBase} href={fileName}>
					<information>
						<title>{title}</title>
						<vendor>{vendor}</vendor>
						<description>{description}</description>
						{ iconName.toSeq	map { it => <icon href={it}/> } }
						{ splashName.toSeq	map { it => <icon href={it} kind="splash"/> } }
						{ if (offlineAllowed) Seq(<offline-allowed/>) else Seq.empty }
					</information>
					<security>
						{ if (allPermissions) Seq(<all-permissions/>) else Seq.empty }
					</security> 
					<resources>
						<j2se version={j2seVersion}  max-heap-size={maxHeapSize + "m"}/>
						{ assets		map { it => <jar href={it.name} main={it.main.toString}/> } }
						{ extensions	map { it => <extension name={it.name} href={it.href} version={it.version getOrElse null}/> } }
					</resources>
					{	architectures map { it =>
							<resources os={it.os} arch={it.arch}>
								{	it.items map {
										case PropertyConf(name, value)	=> <property name={name} value={value}/>
										case NativeLibConf(href)		=> <nativelib href={href}/>
								}	}
							</resources>
					}	}
					<application-desc main-class={mainClass}/>
				</jnlp>
		IO write (targetFile, xml)
	}
	*/
	
	//------------------------------------------------------------------------------
	
	private def keygenTask:Initialize[Task[Unit]] = (
		Keys.streams,
		webstartGenConf,
		webstartKeyConf
	) map keygenTaskImpl
			
	private def keygenTaskImpl(
		streams:TaskStreams,
		genConf:GenConf,
		keyConf:KeyConf
	) {
		streams.log info ("creating webstart key in " + keyConf.keyStore)
		require(genConf	!= null, webstartGenConf.key.label	+ " must be set")
		require(keyConf	!= null, webstartKeyConf.key.label	+ " must be set")
		genkey(keyConf, genConf, streams.log)
	}
	
	private def genkey(keyConf:KeyConf, genConf:GenConf, log:Logger) {
		val rc	= Process("keytool", List(
			"-genkey", 
			"-dname",		genConf.dname, 
			"-validity",	genConf.validity.toString, 
			"-keystore",	keyConf.keyStore.getAbsolutePath,
			"-storePass",	keyConf.storePass, 
			"-keypass",		keyConf.keyPass,
			"-alias",		keyConf.alias
		)) ! log
		if (rc != 0)	sys error ("key gen failed: " + rc)
	}
}
