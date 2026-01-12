package domaindocs4s.collector

import tastyquery.jdk.ClasspathLoaders
import tastyquery.Classpaths.Classpath
import tastyquery.Contexts.Context

import java.io.File
import java.nio.file.{Path, Paths}

type DomainDocsContext = Context

object TastyContext {

  def fromCurrentProcess(): Context = {
    val paths = sys
      .props("java.class.path")
      .split(File.pathSeparator)
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_).toAbsolutePath.normalize())

    val cp = ClasspathLoaders.read(paths)
    Context.initialize(cp)
  }

}
