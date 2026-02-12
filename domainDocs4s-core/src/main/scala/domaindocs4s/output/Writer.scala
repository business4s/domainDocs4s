package domaindocs4s.output

import java.nio.file.{Files, Path}

// TODO: for now it's only for decouple writing from generating, later it should be extended for more complex output handling
object Writer {

  def apply(docs: String, path: String): Unit = {
    val _ = Files.write(Path.of(path), docs.getBytes)
  }

}