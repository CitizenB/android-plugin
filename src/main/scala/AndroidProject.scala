import java.io.File

import sbt._
import Process._

abstract class AndroidProject(info: ProjectInfo) extends DefaultProject(info) with AndroidBase with Proguarder {
  import AndroidBase._

  def aidlName = DefaultAidlName
  def apkbuilderName = DefaultApkbuilderName + osBatchSuffix
  def dxName = DefaultDxName + osBatchSuffix
  def mapsJarName = DefaultMapsJarName
  def classesMinJarName = DefaultClassesMinJarName
  def classesDexName = DefaultClassesDexName
  def packageApkName = artifactBaseName + ".apk"
  def resourcesApkName = DefaultResourcesApkName
  def dxJavaOpts = DefaultDxJavaOpts

  def isWindows = System.getProperty("os.name").startsWith("Windows")
  def osBatchSuffix = if (isWindows) ".bat" else ""

  def dxMemoryParameter = {
    // per http://code.google.com/p/android/issues/detail?id=4217, dx.bat doesn't currently
    // support -JXmx arguments.  For now, omit them in windows.
    if (isWindows) "" else dxJavaOpts
  }

  def androidToolsPath = sdkRoot / "tools"
  def apkbuilderPath = androidToolsPath / apkbuilderName
  def aidlPath = platformToolsPath / aidlName
  def dxPath = platformToolsPath / dxName

  def classesMinJarPath = outputPath / classesMinJarName
  override def proguardOutJar = classesMinJarPath
  def classesDexPath =  outputPath / classesDexName
  def resourcesApkPath = outputPath / resourcesApkName
  def packageApkPath = outputPath / packageApkName

  lazy val aidl = aidlAction
  def aidlAction = aidlTask describedAs("Generate Java classes from .aidl files.")
  def aidlTask = execTask {
    val aidlPaths = descendents(mainSourceRoots, "*.aidl").getPaths
    if(aidlPaths.isEmpty)
      Process(true)
    else
          aidlPaths.toList.map {ap =>
            aidlPath.absolutePath :: "-o" + mainJavaSourcePath.absolutePath :: "-I" + mainJavaSourcePath.absolutePath :: ap :: Nil}.foldLeft(None.asInstanceOf[Option[ProcessBuilder]]){(f, s) => f match{
              case None => Some(s)
              case Some(first) => Some(first ## s)
            }
          }.get
  }

  override def compileAction = super.compileAction dependsOn(aidl)

  lazy val dx = dxAction
  def dxAction = dxTask dependsOn(proguard) describedAs("Convert class files to dex files")
  def dxTask = fileTask(classesDexPath from classesMinJarPath) {
     execTask {<x> {dxPath.absolutePath} {dxMemoryParameter}
        --dex --output={classesDexPath.absolutePath} {classesMinJarPath.absolutePath}
    </x> } run }

  lazy val aaptPackageDebug = aaptPackageActionDebug
  def aaptPackageActionDebug = aaptPackageTask(true) dependsOn(dx) describedAs("Package resources and assets in debug mode")

  lazy val aaptPackageRelease = aaptPackageActionRelease
  def aaptPackageActionRelease = aaptPackageTask(true) dependsOn(dx) describedAs("Package resources and assets in release mode")

  def aaptPackageTask(debug: Boolean) = execTask {<x>
    {aaptPath.absolutePath} package -f -M {androidManifestPath.absolutePath}
        -I {androidJarPath.absolutePath} -F {resourcesApkPath.absolutePath}
       {if (debug) "--debug-mode" else ""}
       {aaptAutoAddOverlayArg}
       {assetDirArgs}
       {resourceDirArgs}
  </x>} dependsOn directory(mainAssetsPath)

  lazy val packageDebug = packageDebugAction
  def packageDebugAction = packageTask(true) dependsOn(aaptPackageDebug) describedAs("Package and sign with a debug key.")

  lazy val packageRelease = packageReleaseAction
  def packageReleaseAction = packageTask(false) dependsOn(aaptPackageRelease) describedAs("Package without signing.")

  lazy val cleanApk = cleanTask(packageApkPath) describedAs("Remove apk package")
  def packageTask(signPackage: Boolean) = execTask {<x>
      {apkbuilderPath.absolutePath}  {packageApkPath.absolutePath}
        {if (signPackage) "" else "-u"} -z {resourcesApkPath.absolutePath} -f {classesDexPath.absolutePath}
        {proguardInJars.get.map(" -rj " + _.absolutePath)}
  </x>} dependsOn(cleanApk)

  lazy val installEmulator = installEmulatorAction
  def installEmulatorAction = installTask(true) dependsOn(packageDebug) describedAs("Install package on the default emulator.")

  lazy val installDevice = installDeviceAction
  def installDeviceAction = installTask(false) dependsOn(packageDebug) describedAs("Install package on the default device.")

  lazy val reinstallEmulator = reinstallEmulatorAction
  def reinstallEmulatorAction = reinstallTask(true) dependsOn(packageDebug) describedAs("Reinstall package on the default emulator.")

  lazy val reinstallDevice = reinstallDeviceAction
  def reinstallDeviceAction = reinstallTask(false) dependsOn(packageDebug) describedAs("Reinstall package on the default device.")

  lazy val startDevice = startDeviceAction
  def startDeviceAction = startTask(false) dependsOn(reinstallDevice) describedAs("Start package on device after installation")

  lazy val startEmulator = startEmulatorAction
  def startEmulatorAction = startTask(true) dependsOn(reinstallEmulator) describedAs("Start package on emulator after installation")

  lazy val uninstallEmulator = uninstallEmulatorAction
  def uninstallEmulatorAction = uninstallTask(true) describedAs("Uninstall package on the default emulator.")

  lazy val uninstallDevice = uninstallDeviceAction
  def uninstallDeviceAction = uninstallTask(false) describedAs("Uninstall package on the default device.")

  def installTask(emulator: Boolean) = adbTask(emulator, "install "+packageApkPath.absolutePath)
  def reinstallTask(emulator: Boolean) = adbTask(emulator, "install -r "+packageApkPath.absolutePath)
  def startTask(emulator: Boolean) = adbTask(emulator, "shell am start -a android.intent.action.MAIN -n "+manifestPackage+"/"+launcherActivity)
  def uninstallTask(emulator: Boolean) = adbTask(emulator, "uninstall "+manifestPackage)

  def adbTask(emulator: Boolean, action: String) = execTask {<x>
      {adbPath.absolutePath} {if (emulator) "-e" else "-d"} {action}
   </x>}

  def launcherActivity: String = {
    for (activity <- (manifest \\ "activity")) {
      for(action <- (activity \\ "action")) {
        val name = action.attribute(manifestSchema, "name").getOrElse(error("action name not defined")).text
        if (name == "android.intent.action.MAIN") {
          val act = activity.attribute(manifestSchema, "name").getOrElse(error("activity name not defined")).text
          if (act.isEmpty) error("activity name not defined")
          return if (act.contains(".")) act else manifestPackage+"."+act
        }
      }
    }
    ""
  }
}
