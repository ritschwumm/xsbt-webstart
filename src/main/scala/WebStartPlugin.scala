package xsbtWebStart

import scala.collection.immutable.{ Seq => ISeq }
import scala.sys.process._
import scala.xml.Elem

import sbt._
import Keys.TaskStreams

import xsbtUtil.implicits._
import xsbtUtil.data._
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
		keyPass:String,
		tsaUrl:Option[String]
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
	val webstartJarsignerVerifyOptions =
		settingKey[Seq[String]]("custom jarsigner options when verifying")
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
							buildDir	= webstartBuildDir.value,
							jarsignerVerifyOptions = webstartJarsignerVerifyOptions.value
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
				webstartJarsignerVerifyOptions := Vector.empty,
				
				webstartBuildDir	:= Keys.crossTarget.value / "webstart",
				
				Keys.watchSources	:= Keys.watchSources.value ++ (webstartManifest.value map Watched.WatchSource.apply)
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
		buildDir:File,
		jarsignerVerifyOptions: Seq[String]
	):File	= {
		// BETTER copy and sign fresh jars only unless they did not exist before
		val assetMap		= assets map { _.flatPathMapping } map (xu.pathMapping anchorTo buildDir)
		streams.log info s"copying assets"
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
				streams.log info s"extending jar manifests"
				val res:Safe[(String,File),ISeq[Unit]]	=
						parDo(freshJars.toVector) { jar =>
							extendManifest(manifest, jar, streams.log)
						}
				failSafe(res) { case (err,file) =>
					streams.log error s"failed to extend manifest of jar $file: $err"
					// try again next time instead of leaving an unextended jar lying around
					file.delete()
				}
			}
			
			if (keyConfig.isEmpty) {
				streams.log info "missing KeyConfig, leaving jar files unsigned"
			}
			keyConfig foreach { keyConfig =>
				streams.log info s"signing jars"
				val res:Safe[(String,File),ISeq[Unit]]	=
						parDo(freshJars.toVector) { jar =>
							signAndVerify(keyConfig, jar, jarsignerVerifyOptions, streams.log)
						}
				failSafe(res) { case (err,file) =>
					streams.log error s"failed to sign jar $file: $err"
					// try again next time instead of leaving an unextended jar lying around
					file.delete()
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
					JnlpAsset(cp.name, cp.main, cp.file.length)
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
	
	/** returns an error message if necessary */
	private def extendManifest(manifest:File, jar:File, log:Logger):Safe[String,Unit]	= {
		val rc	=
				Process("jar", Vector(
					"umf",
					manifest.getAbsolutePath,
					jar.getAbsolutePath
				)) ! log
				
		rc == 0 safeGuard s"jar returned ${rc}".nes
	}
	
	private def signAndVerify(keyConfig:KeyConfig, jar:File, jarsignerVerifyOptions: Seq[String], log:Logger):Safe[String,Unit]	= {
		val args	=
				Vector(
					// "-verbose",
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storepass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass
				) ++ (
					keyConfig.tsaUrl.toVector flatMap { url => Vector("-tsa", url) }
				)
				
		def sign():Safe[String,Unit]	= {
			// sigfile, storetype, provider, providerName
			val rc	=
					Process(
						"jarsigner",
						args ++ Vector(
							// TODO makes the vm crash ???
							// "-signedjar",	jar.getAbsolutePath,
							jar.getAbsolutePath,
							keyConfig.alias
						)
					) ! log
			rc == 0 safeGuard s"jarsigner returned ${rc} (sign)".nes
		}
		
		def verify():Safe[String,Unit]	= {
			val rc	=
					Process(
						"jarsigner",
						Vector("-verify") ++ args ++ jarsignerVerifyOptions ++ Vector(
							jar.getAbsolutePath
						)
					) ! log
			rc == 0 safeGuard s"jarsigner returned ${rc} (verify)".nes
		}
		
		for {
			_	<- sign()
			_	<- verify()
		}
		yield ()
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
			val res	= genkey(keyConfig, genConfig, streams.log)
			failSafe(res) { err =>
				streams.log error s"generating webstart key failed: $err"
			}
		}
	}
	
	private def genkey(keyConfig:KeyConfig, genConfig:GenConfig, log:Logger):Safe[String,Unit]	= {
		val rc	=
				Process("keytool", Vector(
					"-genkey",
					"-dname",		genConfig.dname,
					"-validity",	genConfig.validity.toString,
					"-keystore",	keyConfig.keyStore.getAbsolutePath,
					"-storePass",	keyConfig.storePass,
					"-keypass",		keyConfig.keyPass,
					"-alias",		keyConfig.alias
				)) ! log
				
		rc == 0 safeGuard s"keytool returned ${rc}".nes
	}
	
	//------------------------------------------------------------------------------
	
	import scala.concurrent.{ Future => SFuture, _ }
	import scala.concurrent.duration._
	import ExecutionContext.Implicits.global

	private val timeout	= 1.hour
	
	/** returns an error messages if necessary */
	private def parDo[E,S,T](xs:ISeq[S])(task:S=>Safe[E,T]):Safe[(E,S),ISeq[T]]	= {
		// throws a NumberFormatException on java 9
		//xs.par foreach task
				
		val errors:SFuture[Safe[(E,S),ISeq[T]]]	=
				SFuture
				.sequence (
					xs map { it =>
						SFuture {
							task(it) cata (
								fail	=> Safe fail (fail map (_ -> it)),
								Safe.win
							)
						}
					}
				)
				.map { xs:ISeq[Safe[(E,S),T]] =>
					// TODO xsbtUtil use sequenceSafe when available
					xs traverseSafe identity
				}
			
		Await result (errors, timeout)
	}
	
	//------------------------------------------------------------------------------
	
	private def failSafe[E,T](value:Safe[E,T])(handle:E=>Unit):T	=
			value cata (
				errs	=> {
					errs foreach handle
					throw BuildAbortException
				},
				identity
			)
			
	object BuildAbortException extends RuntimeException("build aborted") with FeedbackProvidedException
}
