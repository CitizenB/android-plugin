import sbt._

// Seems like we should be able to do this with sbt's dependency management somehow.  Note: we are
// using timestamp and size as crude fingerprint; we could abstract this out and have
// CachedFile[Content, Fingerprint] instead.  Thread-safe by default.

abstract class CachedFile[T](p: Path, log: Logger) {
  /**
   * The single override which must be defined by subclasses.  Called with the lock on
   * this held (unless you are using getUnlocked, of course).
   * 
   * @param p the path to load content from
   */
  protected def load(p: Path): T

  /** Most callers should use this method. */
  def get: T = this.synchronized { getUnlocked }

  import CachedFile._

  /** Unsynchronized version of get() */
  def getUnlocked: T = {

    val f = p asFile
    val modTime = Timestamp(f lastModified)
    val size = Size(f length)

    cachedContent match {
      // cached and not stale
      case Some((Fingerprint(cachedTs, cachedSize), cached)) if (cachedTs == modTime && cachedSize == size) => cached

      case _ =>
        log.debug("reloading content from path: "+p)
        log.debug("new modTime="+modTime+" new size="+size+", cached content="+cachedContent)
        val newContent = load(p)
        cachedContent = Some((Fingerprint(modTime, size), newContent))
        newContent
    }
  }

  // timestamp, size, content
  private var cachedContent: Option[(Fingerprint, T)] = None
}

object CachedFile {
 // Avoid confusing timestamp and size, which are both Long
  case class Timestamp(t: Long)
  case class Size(s: Long)
  case class Fingerprint(timestamp: Timestamp, size: Size)
}

class CachedXmlFile(p: Path, log: Logger) extends CachedFile[xml.Elem](p, log) {
  override def load(p: Path) = xml.XML.loadFile(p.asFile)
}
