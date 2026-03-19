package domaindocs4s.viewercy

import java.io.File
import java.net.{HttpURLConnection, ServerSocket, URI}
import scala.sys.process.*
import scala.util.Try

/** Manages a Vite dev server for E2E tests. */
object ViteServer {
  private var process: Option[Process] = None
  private var _port: Int               = 0

  def port: Int = _port
  def url: String = s"http://127.0.0.1:$_port"

  def start(): Unit = synchronized {
    if (process.isDefined) return

    _port = {
      val s = new ServerSocket(0)
      val p = s.getLocalPort
      s.close()
      p
    }

    val viewerDir = new File(
      Option(System.getProperty("viewer.dir"))
        .getOrElse(throw new RuntimeException("System property 'viewer.dir' not set.")),
    )

    require(new File(viewerDir, "package.json").exists(), s"package.json not found in ${viewerDir.getAbsolutePath}")

    val cmd = Seq("npx", "vite", "--config", "vite.config.e2e.js", "--host", "127.0.0.1", "--port", _port.toString, "--strictPort")
    val pb  = scala.sys.process.Process(cmd, viewerDir)
    val logger = ProcessLogger(
      out => System.out.println(s"[vite] $out"),
      err => System.err.println(s"[vite-err] $err"),
    )
    process = Some(pb.run(logger))

    waitForReady(timeoutMs = 60000)
  }

  def stop(): Unit = synchronized {
    process.foreach(_.destroy())
    process = None
  }

  private def waitForReady(timeoutMs: Long): Unit = {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (isReady) return
      Thread.sleep(500)
    }
    throw new RuntimeException(s"Vite dev server did not start within ${timeoutMs}ms on port $_port")
  }

  private def isReady: Boolean = Try {
    val conn = new URI(s"http://127.0.0.1:$_port/").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(2000)
    conn.setReadTimeout(2000)
    val code = conn.getResponseCode
    conn.disconnect()
    code == 200
  }.getOrElse(false)
}
