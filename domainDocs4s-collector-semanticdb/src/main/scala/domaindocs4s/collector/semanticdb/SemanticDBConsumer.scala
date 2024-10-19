package domaindocs4s.collector.semanticdb

import scala.meta.internal.semanticdb.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.StreamConverters._

object SemanticDBConsumer {
  def main(args: Array[String]): Unit = {
    // Path to the generated .semanticdb files
    val base = Path.of("domainDocs4s-test-input/target/scala-3.3.4/meta/META-INF/semanticdb")

    for {
      file     <- Files.walk(base).toScala(List)
      if file.toString.endsWith(".semanticdb")
      bytes     = Files.readAllBytes(file)
      document <- TextDocuments.parseFrom(bytes).documents
    } {
      println(s"File: ${document.uri}")
      document.symbols.foreach { symbol =>
        println(s"Symbol: ${symbol.symbol}")

        // Check if the symbol has any annotations
        symbol.annotations.foreach { annotation =>
          println(s"Annotation: ${annotation.tpe}")
        }
      }
    }
  }
}
