import sbt._

// Standardize the location of generated sources (Note: this location is defined in the
// guts of sbt, in BuilderProject#PluginBuilderProject, which is not easily accessible).
// Note: this is all rather ad-hoc; there isn't really any framework for
// macro-ish/staged code generation in sbt afaik.  (Or for that matter in scala,
// although see http://infoscience.epfl.ch/record/150347/files/gpce63-rompf.pdf by Rompf
// and Odersky for possible future developments).

trait ManagedSourcePath extends DefaultProject {
  // FIXME:  missing some {main,test} {scala,java} combinations

  def managedSources: Path = path("src_managed")

  // where to output generated scala and java, respectively
  def managedScalaPath: Path = Path.fromString(managedSources, "main/scala")
  def managedJavaPath: Path = Path.fromString(managedSources, "main/java")

  abstract override def mainSourceRoots = super.mainSourceRoots +++ (managedSources / "main" / "scala" ##) +++ (managedSources / "main" / "java" ##)

  override def cleanAction = super.cleanAction dependsOn cleanTask(managedSources)
}
