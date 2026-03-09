package domaindocs4s.architecture.lineage

trait LineageLogger {
  def log(message: String): Unit

  def timed[A](label: String)(f: => A): A = {
    log(s"$label ...")
    val start = System.nanoTime()
    val result = f
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    log(s"$label completed in ${elapsedMs}ms")
    result
  }
}

object LineageLogger {

  val noop: LineageLogger = _ => ()

  val println: LineageLogger = msg => scala.Predef.println(s"[lineage] $msg")

  /** Returns a logger based on the `domaindocs4s.lineage.logging` system property.
    * Set to "true" to enable println logging; disabled by default.
    */
  def fromSystemProperty(): LineageLogger =
    if (sys.props.getOrElse("domaindocs4s.lineage.logging", "false").toBoolean) println
    else noop
}
