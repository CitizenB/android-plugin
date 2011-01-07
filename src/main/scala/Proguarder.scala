import sbt._

import java.io.File

import proguard.{ Configuration => ProGuardConfiguration, ProGuard, ConfigurationParser }

trait Proguarder extends AndroidBase {
  /** String (FIXME) with any additional proguard args */
  def proguardOption = ""
  def proguardInJars = runClasspath --- proguardExclude
  def proguardExclude = libraryJarPath +++ mainCompilePath +++ mainResourcesPath +++ fullClasspath(Configurations.Provided)

  /**
   * Where to put the final proguarded jar.  Note that proguard allows multiple output jars, zips,
   * and directories, but in the context of Android deployment, everything gets put into one jar.
   */
  def proguardOutJar: Path

  //def allInJars: Iterable[Path] // what gets passed as "-injars"
  // mainCompilepath
  // scala libs (for android)
  // proguardInJars

  lazy val proguard = proguardAction
  def proguardAction = proguardTask dependsOn(compile) describedAs("Optimize class files.")
  def proguardTask = task {
    val args = "-injars" :: mainCompilePath.absolutePath+File.pathSeparator+
                            scalaLibraryJar.getAbsolutePath+"(!META-INF/MANIFEST.MF,!library.properties)"+
                            (if (!proguardInJars.getPaths.isEmpty) File.pathSeparator+proguardInJars.getPaths.map(_+"(!META-INF/MANIFEST.MF)").mkString(File.pathSeparator) else "") ::
               "-outjars" :: proguardOutJar.absolutePath ::
               "-libraryjars" :: libraryJarPath.getPaths.mkString(File.pathSeparator) ::
               "-dontwarn scala.**" ::
               "-ignorewarn" ::
               "-dontoptimize" ::
               "-dontobfuscate" ::
               "-keep public class * extends android.app.Activity" ::
               "-keep public class * extends android.app.Service" ::
               "-keep public class * extends android.appwidget.AppWidgetProvider" ::
               "-keep public class * extends android.content.BroadcastReceiver" ::
               "-keep public class * extends android.content.ContentProvider" ::
               "-keep public class * extends android.view.View" ::
               "-keep public class * extends android.app.Application" ::
               "-keep public class "+manifestPackage+".** { public protected *; }" ::
               "-keep public class * implements junit.framework.Test { public void test*(); }" ::
               proguardOption ::
               Nil

    val config = new ProGuardConfiguration
    new ConfigurationParser(args.toArray[String], info.projectPath.asFile).parse(config)
    new ProGuard(config).execute
    None
  }

  def scalaLibraryJar = buildScalaInstance.libraryJar
}
