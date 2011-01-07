import sbt._

// Borrowed from sbt (xsbt/project/build/Helpers.scala)
trait EmptyTask extends Project {
  val emptyTask = task {None}
}
