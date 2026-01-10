package domaindocs4s.utils

import java.io.File
import java.nio.file.{Path, Paths}

object TestClasspath extends App {

  lazy val current: List[Path] =
    sys
      .props("java.class.path")
      .split(File.pathSeparator)
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))

}
