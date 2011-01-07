import sbt._

trait AndroidDefaults extends AndroidBase {
  override def androidPlatformName = "android-7"

  // try to keep all config in the sbt project rather than in random environment vars
  override lazy val sdkRoot = Path.userHome / "android" / "sdk"
}
