package domaindocs4s.collector

import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

import java.io.File
import java.net.URI
import java.nio.file.{FileSystems, Path, Paths}

type DomainDocsContext = Context

object TastyContext {

  def fromCurrentProcess(): Context = {
    val appCp: List[Path] = sys
      .props("java.class.path")
      .split(File.pathSeparator)
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(p => Paths.get(p).toAbsolutePath.normalize())

    val jdkBase: List[Path] =
      try {
        val jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"))
        List(jrtFs.getPath("modules", "java.base"))
      } catch {
        case _: Throwable => Nil
      }

    val cp = ClasspathLoaders.read(appCp ++ jdkBase)
    Context.initialize(cp)
  }

}
