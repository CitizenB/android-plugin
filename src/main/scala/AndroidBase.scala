import sbt._
import Process._

import java.util.Properties
import java.io.FileInputStream

import collection.immutable

// Basic info about android sdk, android.jar, add-on jars, etc.  This is shared between
// AndroidProject (produces something installable on android) and AndroidLibrary (which
// only provides resources and code for other projects)
trait AndroidBase extends DefaultProject with ManagedSourcePath {
  /** This is the one required override.  It should be "android-$apiLevelOrName" eg "android-7" or "android-2.1" */
  def androidPlatformName: String

  override def compileAction = super.compileAction dependsOn(aaptGenerate)

  lazy val aaptGenerate = aaptGenerateAction
  def aaptGenerateAction = aaptGenerateTask describedAs("Generate R.java.")

  // FIXME: for lazy libs, we want to place an (identical modulo package name) copy of
  // merged resources in the package of every library project, but located in the
  // current project.  This way code from library projects will be able to refer to
  // resources using the library project's package.  Hopefully proguard will weed out
  // the unused resources, and library users can directly refer to library project
  // resources by fully-qualified package names, if needed.

  def aaptGenerateTask = execTask {<x>
                                   {aaptPath.absolutePath} package -m -M {androidManifestPath.absolutePath}
                                   -I {androidJarPath(targetSdkVersion).absolutePath} -J {managedJavaPath.absolutePath}
                                   {aaptAutoAddOverlayArg}
                                   {resourceDirArgs}
                                   </x>} dependsOn directory(managedJavaPath)

  // Note:  --auto-add-overlay is what allows "lazy" library projects; without this undefined resource references in library projects
  // will get errors like "error: Resource at app_name appears in overlay but not in the base package; use <add-resource> to add."
  def aaptAutoAddOverlay = true
  def aaptAutoAddOverlayArg = if (aaptAutoAddOverlay) "--auto-add-overlay" else ""

  import AndroidBase._

  lazy val sdkRoot = {
    val envs = List("ANDROID_SDK_HOME", "ANDROID_SDK_ROOT")
    val paths = for { e <- envs; p = System.getenv(e); if p != null } yield p
    if (paths.isEmpty) error("You need to set " + envs.mkString(" or ") + " or override sdkRoot in your project")
    Path.fromFile(paths.first)
  }

  def aaptName = DefaultAaptName // note: this is an .exe file in windows
  def aaptPath = platformToolsPath / aaptName

  val sdkPathsRearranged = 8
  def genericToolsPath = sdkRoot / "tools"
  def platformToolsPath = Path.fromString(sdkRoot, (if (sdkRevision < sdkPathsRearranged) "platforms/android-"+apiLevel else "platform-tools"))

  def adbName = DefaultAdbName
  def adbPath = (if (sdkRevision < sdkPathsRearranged) genericToolsPath else platformToolsPath) / adbName

  def androidManifestName = DefaultAndroidManifestName
  def androidManifestPath = mainSourcePath / androidManifestName
  def androidJarName = DefaultAndroidJarName
  def androidJarPath(api: Int) = sdkRoot / "platforms" / ("android-"+api) / androidJarName
  def libraryJarPath = androidJarPath(apiLevel) +++ addonsJarPath

  // Note: we want to add Android platform and add-on libraries to the provided classpath, not the
  // unmanaged classpath.  The latter gets added to all classpaths, which causes messiness during
  // deployment scenarios (hello, proguard).
  override def managedClasspath(c: Configuration) =
    super.managedClasspath(c) +++ (if (c == Configurations.Provided) libraryJarPath else Path.emptyPathFinder)

  lazy val cachedManifest = new CachedXmlFile(androidManifestPath, log)
  def manifest: xml.Elem = cachedManifest.get

  lazy val sdkRevision: Int = getSdkRevision(sdkRoot)

  /**
   * This is the api level which we compile against.  It is the minimum api level, but aapt can use
   * things at the target api level since xml files can include newer tags/attribs which will
   * (allegedly) be ignored during deployment.  Defaults to the uses-sdk minSdkVersion attribute
   * or, if that is not present, the level implied by the platform name (eg android-2.1 is api
   * level 7).
   */
  def apiLevel: Int = minSdkVersion.getOrElse(platformName2ApiLevel(androidPlatformName))

  def minSdkVersion = usesSdk("minSdkVersion")
  def targetSdkVersion: Int = usesSdk("targetSdkVersion").orElse(minSdkVersion).getOrElse(apiLevel)
  def manifestPackage = manifest.attribute("package").getOrElse(error("package not defined")).text
  def usesSdk(s: String): Option[Int] = (manifest \ "uses-sdk").first.attribute(manifestSchema, s).map(_.text.toInt)

  def addonsPath = sdkRoot / "add-ons" / ("google_apis-" + apiLevel) / "libs"
  def mapsJarPath = addonsPath / DefaultMapsJarName

  def addonsJarPath = Path.lazyPathFinder {
    for {
      lib <- manifest \ "application" \ "uses-library"
      p = lib.attribute(manifestSchema, "name").flatMap {
        _.text match {
          case "com.google.android.maps" => Some(mapsJarPath)
          case _ => None
        } 
      }   
      if p.isDefined
    } yield p.get
  }

  def assetsDirectoryName = DefaultAssetsDirectoryName
  def resDirectoryName = DefaultResDirectoryName
  def mainAssetsPath = mainSourcePath / assetsDirectoryName
  def mainResPath = mainSourcePath / resDirectoryName

  // Collect some path-related info for every android project in the dependency dag,
  // starting with this project.  Because the first match wins for aapt -S and its ilk,
  // we want to put ourself first, followed by the dependencies in the order specified.
  // Presumably info.dependencies is in the order specified in project file.  If we ever
  // need more control than this, we need some place to specify priority.  Different
  // users of library project might want different orderings I suppose.
  def collectFromDag(includeSelf: Boolean, fn: AndroidBase => PathFinder): Iterable[PathFinder] =
    // Note: we are assuming that the current project always ends up at the end of its
    // dag, since a project always depends on all its dependencies, oddly enough.
    (if (includeSelf) topologicalSort else topologicalSort.dropRight(1)).reverse.flatMap { case ap: AndroidBase => Some(fn(ap)); case _ => None }

  def collectFromDagFlattened(includeSelf: Boolean, fn: AndroidBase => PathFinder): PathFinder =
    collectFromDag(includeSelf, fn).foldLeft(Path.emptyPathFinder) { (a, b) => a +++ b }

  def allResourceDirs = collectFromDag(true, _.mainResPath)
  def allAssetDirs = collectFromDag(true, _.mainAssetsPath)

  /** Prefix every item of dirs with -flag, eg -S dir1 -S dir2 etc. */
  def makeArgs(flag: Char, dirs: Iterable[PathFinder]) = (for {
    pf <- dirs
    p <- pf.get
  } yield{ "-%c %s".format(flag, p.absolutePath) }) mkString " "
  def resourceDirArgs = makeArgs('S', allResourceDirs)
  def assetDirArgs = makeArgs('A', allAssetDirs)

  def directory(dir: Path) = fileTask(dir :: Nil) {
    FileUtilities.createDirectory(dir, log)
  }
}

object AndroidBase {
  val DefaultAaptName = "aapt"
  val DefaultAdbName = "adb"
  val DefaultAidlName = "aidl"
  val DefaultApkbuilderName = "apkbuilder"
  val DefaultDxName = "dx"
  val DefaultAndroidManifestName = "AndroidManifest.xml"
  val DefaultAndroidJarName = "android.jar"
  val DefaultMapsJarName = "maps.jar"
  val DefaultAssetsDirectoryName = "assets"
  val DefaultResDirectoryName = "res"
  val DefaultClassesMinJarName = "classes.min.jar"
  val DefaultClassesDexName = "classes.dex"
  val DefaultResourcesApkName = "resources.apk"
  val DefaultDxJavaOpts = "-JXmx512m"
  val manifestSchema = "http://schemas.android.com/apk/res/android"

  // bails if can't map name to an api level
  def platformName2ApiLevel(name: String): Int = name match {
    case intLevel(i) => i.toInt
    case otherLevel(s) => levelMap(s)
  }

  private val levelMap: immutable.Map[String, Int] = immutable.HashMap(
    "1.0" -> 1,
    "1.1" -> 2,
    "1.5" -> 3,
    "1.6" -> 4,
    "2.0" -> 5,
    // no level 6
    "2.1" -> 7,
    "2.2" -> 8,
    "2.3" -> 9
  )

  private val intLevel = """^android-(\d+)$""".r
  private val otherLevel = """^android-(\s+)$""".r

  /** interpret a path as a properties file */
  implicit def path2props(p: Path): Properties = {
    val retval = new Properties
    retval.load(new FileInputStream(p.asFile))
    retval
  }

  def getSdkRevision(sdkRoot: Path): Int = {
    val maybeRevision = sdkRoot / "tools" / "source.properties" getProperty "Pkg.Revision" 

    // no Option.apply in scala 2.7, doh
    if (maybeRevision == null)
      throw new Exception("cannot find sdk revision")
    else
      maybeRevision.toInt
  }
}
