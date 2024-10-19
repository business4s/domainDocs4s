package domaindocs4s.collector.tasty

import java.nio.file.{Files, Path}
import scala.tasty.inspector.TastyInspector
import scala.jdk.StreamConverters.*

object Main {

  def main(args: Array[String]): Unit = {
//    val base = Path.of("domainDocs4s-test-input/target/scala-3.3.4/classes")
    val base = Path.of("domainDocs4s-test-input/target/scala-3.3.4/classes")
    val api  = Path.of("domainDocs4s-api/target/scala-3.3.4/classes")

    val tastyFiles = listTasty(base) ++ listTasty(api)

    val inspector = new MyInspector
    TastyInspector.inspectTastyFiles(tastyFiles)(inspector)
  }

  def listTasty(dir: Path): List[String] = {
    Files
      .walk(dir)
      .toScala(List)
      .map(_.toString)
      .filter(_.endsWith(".tasty"))
  }

}
