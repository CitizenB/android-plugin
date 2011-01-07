import sbt._

abstract class AndroidLibraryProject(info: ProjectInfo) extends DefaultProject(info) with AndroidBase

/**
 * Eager libraries cannot have any unbound references (eg resource names to be resolved
 * by client projects), but can be compiled ahead of time.
 */
abstract class EagerLibraryProject(info: ProjectInfo) extends AndroidLibraryProject(info) {
  // test case:  testproj project which has elp: EagerLibraryProject as a dependency

  // 1.  aapt-generate on testproj should cause aapt-generate on elp, which should generate
  // R.java (and possibly TR.scala) in elp's managedScalaPath with package of elp's
  // manifest.

  // 2.  compile on testproj should cause compile on elp, with output to elp's outputPath

  // 3.  for each resource only in testproj, that resource should show up in R.java

  // 4.  for each resource only in elp, that resource should show up in R.java

  // 5.  for each resource in multiple projects, the library with highest prio (counting testproj as inf) should show up in R.java
}

/**
 * Lazy libraries can have unbound references, but cannot be compiled ahead of time.  More
 * precisely, the parts which (directly or indirectly) depend on R cannot be compiled ahead
 * of time.
 * 
 * This class produces no artifacts of its own.  Projects which depend on this have to amend
 * their source and resource paths to include this project's source and resource paths.  The
 * IncludeLibraries trait can be used to do this.
 */
abstract class LazyLibraryProject(info: ProjectInfo) extends AndroidLibraryProject(info) with EmptyTask {
  // test case:  testproj which has llp: LazyLibraryProject as dep, and llp has unbound (scala or resource) refs which are resolved by testproj

  // 1.  after aapt-generate or compile on llp, nothing should be changed in llp

  // 2.  after aapt-generate on testproj, nothing should be changed in llp, and there should be
  // generated R.java (and TR.scala) in testproj dir with package of llp, in testproj's output dir

  // 3, 4, 5 same as EagerLibraryProject

  override def compileAction = emptyTask
  override def aaptGenerateAction = emptyTask
}

/**
 * Defined only for android projects, this trait collects all source paths (resp. resource
 * paths) for use during this project's compile (resp. aapt) actions.
 */
// trait IncludeLibraries extends AndroidProject {
//   // avoid infinite recursion...
//   // FIXME:  only want to include lazy libs in source roots; eager libs are already compiled in their own project's compile action
//   // override def mainSourceRoots = super.mainSourceRoots +++ collectFromDagFlattened(false, _.mainSourceRoots)
//   //override def mainResPath = collectFromDagFlattened(_.mainResPath)
// }
