package domaindocs4s.collector.classgraph

import io.github.classgraph.ClassGraph
import scala.jdk.CollectionConverters.*

object Main {

  def main(args: Array[String]): Unit = {

    val pkg = "domaindocs4stest"

    val scanResult = // Assign scanResult in try-with-resources
      new ClassGraph() // Create a new ClassGraph instance
        .verbose() // If you want to enable logging to stderr
        .enableAllInfo() // Scan classes, methods, fields, annotations
        .acceptPackages(pkg) // filter subpackages
        .scan()

    scanResult.getClassesWithAnnotation(classOf[domaindocs4s.java.domainDoc])
      .asScala
      .foreach(x => println(x.getName))
  }

}
