package domaindocs4s.collector.tastyquery

import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

import java.nio.file.Path

object Main {

  def main(args: Array[String]): Unit = {

    val cp = ClasspathLoaders.read(
      List(
        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-api/target/scala-3.5.1/classes"),
        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-test-input/target/scala-3.5.1/classes"),
      ),
    )
    val ctx = Context.initialize(cp)

    val foo = ctx.findStaticType("domaindocs4stest.Foo")
    println(foo.annotations.map(_.argIfConstant(0)))
    val pkg = ctx.findPackage("domaindocs4stest")
    println(pkg.annotations) // empty :(
  }
}
