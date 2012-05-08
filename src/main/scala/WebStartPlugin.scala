import sbt._

import Keys.Classpath
import Keys.TaskStreams
import Project.Initialize
import classpath.ClasspathUtilities

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
		maxHeapSize:Int
	)
			
	//------------------------------------------------------------------------------
	//## exported
	
	val webstartKeygen			= TaskKey[Unit]("webstart-keygen")
	val webstartBuild			= TaskKey[File]("webstart")
	val webstartAssets			= TaskKey[Seq[Asset]]("webstart-assets")
	val webstartOutputDirectory	= SettingKey[File]("webstart-output-directory")
	val webstartResources		= SettingKey[PathFinder]("webstart-resources")
	val webstartGenConf			= SettingKey[GenConf]("webstart-gen-conf")
	val webstartKeyConf			= SettingKey[KeyConf]("webstart-key-conf")                   
	val webstartJnlpConf		= SettingKey[Seq[JnlpConf]]("webstart-jnlp-conf")
	val webstartExtraFiles		= TaskKey[Seq[File]]("webstart-extra-files")
		
	// webstartJnlp		<<= (Keys.name) { it => it + ".jnlp" },
	lazy val allSettings	= Seq(
		webstartKeygen				<<= keygenTask,
		webstartBuild				<<= buildTask,
		webstartAssets				<<= assetsTask,
		webstartOutputDirectory		<<= (Keys.crossTarget) { _ / "webstart" },
		webstartResources			<<= (Keys.sourceDirectory in Runtime) { _ / "webstart" },
		webstartGenConf				:= null,
		webstartKeyConf				:= null,
		webstartJnlpConf			:= Seq.empty,
		webstartExtraFiles			:= Seq.empty
	)
	
	case class Asset(main:Boolean, fresh:Boolean, jar:File) {
		val name:String	= jar.getName
	}
	
	//------------------------------------------------------------------------------
	//## tasks
	
	private def buildTask:Initialize[Task[File]] = (
		Keys.streams,
		webstartAssets,
		webstartKeyConf,
		webstartJnlpConf,
		webstartResources,
		webstartExtraFiles,
		webstartOutputDirectory
	) map buildTaskImpl
	
	private def buildTaskImpl(
		streams:TaskStreams,	
		assets:Seq[Asset],
		keyConf:KeyConf,
		jnlpConfs:Seq[JnlpConf],
		webstartResources:PathFinder,
		extraFiles:Seq[File],
		outputDirectory:File
	):File	= {
		// require(jnlpConf	!= null, webstartJnlpConf.key.label		+ " must be set")
		
		val freshAssets	= assets filter { _.fresh }
		if (keyConf != null && freshAssets.nonEmpty) {
			streams.log info ("signing jars")
			freshAssets.par foreach { asset =>
				signAndVerify(keyConf, asset.jar, streams.log)
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
		val confFiles:Seq[(JnlpConf,File)]	= jnlpConfs map { it => (it, outputDirectory / it.fileName) }
		confFiles foreach { case (jnlpConf, jnlpFile) => writeJnlp(jnlpConf, assets, jnlpFile) }
		val jnlpFiles	= confFiles map { _._2 }
		
		// TODO check
		// Keys.defaultExcludes
		streams.log info ("copying resources")
		val resourcesToCopy	=
				for {
					dir		<- webstartResources.get
					file	<- dir.***.get
					target	= Path.rebase(dir, outputDirectory)(file).get
				}
				yield (file, target)
		val resourcesCopied	= IO copy resourcesToCopy
	
		streams.log info ("copying extra files")
		val extraCopied	= IO copy (extraFiles map { it => (it, outputDirectory / it.getName) })
		
		streams.log info ("cleaning up")
		val allFiles	= (outputDirectory * "*").get.toSet
		val assetJars	= assets map { _.jar }
		val obsolete	= allFiles -- assetJars -- resourcesCopied -- extraCopied -- jnlpFiles 
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
	
	private def writeJnlp(jnlpConf:JnlpConf, assets:Seq[Asset], targetFile:File) {
		val xml	= 
				"""<?xml version="1.0" encoding="utf-8"?>""" + "\n" +
				<jnlp spec="1.5+" codebase={jnlpConf.codeBase} href={jnlpConf.fileName}>
					<information>
						<title>{jnlpConf.title}</title>
						<vendor>{jnlpConf.vendor}</vendor>
						<description>{jnlpConf.description}</description>
						{ jnlpConf.iconName.toSeq map { it => <icon href={it}/> } }
						{ jnlpConf.splashName.toSeq map { it => <icon href={it} kind="splash"/> } }
						{ if (jnlpConf.offlineAllowed) Seq(<offline-allowed/>) else Seq.empty }
					</information>
					<security>
						{ if (jnlpConf.allPermissions) Seq(<all-permissions/>) else Seq.empty }
					</security> 
					<resources>
						<j2se version={jnlpConf.j2seVersion}  max-heap-size={jnlpConf.maxHeapSize + "m"}/>
						{ assets map { it => <jar href={it.name} main={it.main.toString} /> } }
					</resources>
					<application-desc main-class={jnlpConf.mainClass}/>
				</jnlp>
		IO write (targetFile, xml)
	}
	
	//------------------------------------------------------------------------------
	//## jar files
	
	private def assetsTask:Initialize[Task[Seq[Asset]]]	= (
		// BETTER use dependencyClasspath and products instead of fullClasspath?
		// BETTER use exportedProducts instead of products?
		Keys.streams,
		Keys.products in Runtime,
		Keys.fullClasspath in Runtime,
		Keys.cacheDirectory,
		webstartOutputDirectory
	) map assetsTaskImpl
		
	private def assetsTaskImpl(
		streams:TaskStreams,
		products:Seq[File],
		fullClasspath:Classpath,
		cacheDirectory:File,
		outputDirectory:File
	):Seq[Asset]	= {
		// NOTE for directories, the package should be named after the artifact they come from
		val (archives, directories)	= fullClasspath.files.distinct partition ClasspathUtilities.isArchive
		
		streams.log info ("creating directory jars")
		val directoryAssets	= directories.zipWithIndex map { case (source, index) =>
			val main	= products contains source
			val cache	= cacheDirectory / webstartAssets.key.label / index.toString
			val target	= outputDirectory / (index + ".jar")
			val fresh	= jarDirectory(source, cache, target)
			Asset(main, fresh, target)
		}
		
		streams.log info ("copying library jars")
		val archiveAssets	= archives map { source =>
			val main	= products contains source
			val	target	= outputDirectory / source.getName 
			val fresh	= copyArchive(source, target)
			Asset(main, fresh, target)
		}
		
		val assets	= archiveAssets ++ directoryAssets
		val (freshAssets,unchangedAssets)	= assets partition { _.fresh }
		streams.log info (freshAssets.size + " fresh jars, " + unchangedAssets.size + " unchanged jars")
		
		assets
	}
	
	private def copyArchive(sourceFile:File, targetFile:File):Boolean	= {
		val fresh	= !targetFile.exists || sourceFile.lastModified > targetFile.lastModified
		if (fresh) {
			IO copyFile (sourceFile, targetFile)
		}
		fresh
	}
	
	private def jarDirectory(sourceDir:File, cacheDir:File, targetFile:File):Boolean	= {
		import Predef.{conforms => _, _}
		import collection.JavaConversions._
		import Types.:+:
		
		import sbinary.{DefaultProtocol,Format}
		import DefaultProtocol.{FileFormat, immutableMapFormat, StringFormat, UnitFormat}
		import Cache.{defaultEquiv, hConsCache, hNilCache, streamFormat, wrapIn}
		import Tracked.{inputChanged, outputChanged}
		import FileInfo.exists
		import FilesInfo.lastModified
		
		implicit def stringMapEquiv: Equiv[Map[File, String]] = defaultEquiv
		
		val sources		= (sourceDir ** -DirectoryFilter get) x (Path relativeTo sourceDir)
		
		def makeJar(sources:Seq[(File, String)], jar:File) {
			IO delete jar
			IO zip (sources, jar)
		}
		
		val cachedMakeJar = inputChanged(cacheDir / "inputs") { (inChanged, inputs:(Map[File, String] :+: FilesInfo[ModifiedFileInfo] :+: HNil)) =>
			val sources :+: _ :+: HNil = inputs
			outputChanged(cacheDir / "output") { (outChanged, jar:PlainFileInfo) =>
				val fresh	= inChanged || outChanged
				if (fresh) {
					makeJar(sources.toSeq, jar.file)
				}
				fresh
			}
		}
		val sourcesMap		= sources.toMap
		val inputs			= sourcesMap :+: lastModified(sourcesMap.keySet.toSet) :+: HNil
		val fresh:Boolean	= cachedMakeJar(inputs)(() => exists(targetFile))
		fresh
	}
	
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
