package domaindocs4s.collector

import domaindocs4s.utils.TestClasspath
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

class DocumentationTest extends AnyFreeSpec {

  given ctx: Context = Context.initialize(ClasspathLoaders.read(TestClasspath.current))

  private val pkg       = "domaindocs4s.collector.fixtures.documentation"
  private val collector = new TastyQueryCollector
  private val docs      = collector.collectSymbols(pkg)

  private def names(d: Documentation): List[String] =
    d.symbols.map(_.symbol.name.toString)

  private def pathOf(name: String): List[String] = {
    val ds = docs.symbols
      .find(_.symbol.name.toString == name)
      .getOrElse(fail(s"Symbol '$name' not found"))
    ds.path.map(ps => ps.name.toString).toList
  }

  "documentation" - {

    "contains all annotated elements" in {

      val expected = List(
        "ExampleEntity",
        "documentedMethod",
        "documentedField",
        "DocumentedType",
        "Nested$",
        "nestedMethod",
        "ExampleEntity$",
        "companionVal",
        "UtilityObject$",
        "utilFunction",
        "ExampleStatus",
        "Active",
        "Inactive",
      )

      names(docs) should contain theSameElementsAs expected

    }

    "does not contain elements without annotation" in {

      val notExpected = Set("internalHelper", "rawVal")

      names(docs) should contain noElementsOf notExpected

    }

    "elements contain correct paths" in {

      pathOf("ExampleEntity") shouldEqual List("documentation")
      pathOf("documentedMethod") shouldEqual List("documentation", "ExampleEntity")
      pathOf("documentedField") shouldEqual List("documentation", "ExampleEntity")
      pathOf("DocumentedType") shouldEqual List("documentation", "ExampleEntity")
      pathOf("Nested$") shouldEqual List("documentation", "ExampleEntity")
      pathOf("nestedMethod") shouldEqual List("documentation", "ExampleEntity", "Nested$")

      pathOf("ExampleEntity$") shouldEqual List("documentation")
      pathOf("companionVal") shouldEqual List("documentation", "ExampleEntity$")

      pathOf("UtilityObject$") shouldEqual List("documentation")
      pathOf("utilFunction") shouldEqual List("documentation", "UtilityObject$")

      pathOf("ExampleStatus") shouldEqual List("documentation")
      pathOf("Active") shouldEqual List("documentation", "ExampleStatus$")
      pathOf("Inactive") shouldEqual List("documentation", "ExampleStatus$")

    }

  }

}
