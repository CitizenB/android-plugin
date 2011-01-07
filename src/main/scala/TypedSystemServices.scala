import sbt._
import scala.xml._

object TSSHelper {
  // FIXME:  should go elsewhere eg text utils or maybe RichString (eventually)
  def firstLetterLower(s: String) = if (s == "") "" else s(0).toLowerCase + s.substring(1)
  def firstWordLower(xs: List[String]) = xs match {
    case hd :: tl => firstLetterLower(hd) :: tl
    case _ => xs
  }

  val StripServiceRE = """^([A-Z_]+)_SERVICE$""".r

  // Note the exception for layout which has type android.view.LayoutInflater
  // rather than the usual ...Manager.
  def toMethodName(sn: String) = sn match {
    case "LAYOUT_INFLATER_SERVICE" => "layoutInflater"
    case StripServiceRE(s) => (firstWordLower(s split('_') map { _.toLowerCase.capitalize } toList) mkString)+"Service"
  }

  def toClassName(sn: String) = sn match {
    case "LAYOUT_INFLATER_SERVICE" => "LayoutInflater"
    case StripServiceRE(s) => (s split('_') map { _.toLowerCase.capitalize } mkString)+"Manager"
  }

  def genMethod(serviceName: String, pkg: String) =
    "def %s = getSystemService(Context.%s).asInstanceOf[%s.%s]".format(
      toMethodName(serviceName), serviceName, pkg, toClassName(serviceName)
    )

  // FIXME: use reflection on android.content.Context: look for all static
  // Strings which end in "_SERVICE".  Could also burrow around in android jar
  // looking for which package these are in, bailing on no- or multiple-match.
  def services = List(
    ("INPUT_METHOD_SERVICE", "android.view.inputmethod"),
    ("LAYOUT_INFLATER_SERVICE", "android.view"),
    ("NOTIFICATION_SERVICE", "android.app")
  )

  def allServiceDefs = services map { case (name, pkg) => genMethod(name, pkg) }
}

trait TypedSystemServices extends AndroidBase {
  def typedServices = managedScalaPath / "TypedSystemServices.scala"

  override def compileAction = super.compileAction dependsOn generateTypedServices
  override def watchPaths = super.watchPaths +++ androidJarPath

  import TSSHelper.allServiceDefs

  /** File task that generates `typedService` if it doesn't exist */
  lazy val generateTypedServices = fileTask(List(typedServices)) {
    clobberTypedServices.run
  } describedAs ("Produce a file "+typedServices+" that contains typed service definitions")

  lazy val clobberTypedServices = task {
    FileUtilities.write(typedServices.asFile,
    """     |package %s
            |
            |import android.content.Context
            |
            |trait TypedSystemServices extends Context {
            |%s
            |}
            |""".stripMargin.format(manifestPackage, allServiceDefs map { "  "+_ } mkString "\n"), log
    )
    None
  } describedAs ("Unconditionally produce a file "+typedServices+" that contains typed service definitions")
}
