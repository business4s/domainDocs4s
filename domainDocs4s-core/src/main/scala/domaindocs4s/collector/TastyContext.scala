package domaindocs4s.collector

import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

import java.io.File
import java.nio.file.Paths

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
