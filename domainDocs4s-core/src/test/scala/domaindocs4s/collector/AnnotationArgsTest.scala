package domaindocs4s.collector

import org.scalatest.freespec.AnyFreeSpec

class AnnotationArgsTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  "collector" - {

    "handles missing args and constant args" - {

      val docs = collector.collectSymbols("domaindocs4s.collector.fixtures.annotationargs")

      def findSymbol(name: String) =
        docs.symbols.find(_.symbol.name.toString == name).getOrElse(fail(s"Symbol '$name' not found"))

      "annotation without args" in {
        val symbol = findSymbol("Doc_NoArgs")
        assert(symbol.nameOverride.isEmpty)
        assert(symbol.description.isEmpty)
        assert(symbol.symbol.name.toString == "Doc_NoArgs")
      }

      "annotation with constant description arg" in {
        val symbol = findSymbol("Doc_DescLiteral")
        assert(symbol.nameOverride.isEmpty)
        assert(symbol.description.contains("Test description (literal)."))
        assert(symbol.symbol.name.toString == "Doc_DescLiteral")
      }

      "annotation with constant name arg" in {
        val symbol = findSymbol("Doc_NameLiteral")
        assert(symbol.nameOverride.contains("NameLiteral"))
        assert(symbol.description.isEmpty)
        assert(symbol.symbol.name.toString == "Doc_NameLiteral")
      }

      "annotation with constant description and name args" in {
        val symbol = findSymbol("Doc_BothLiterals")
        assert(symbol.nameOverride.contains("BothLiterals"))
        assert(symbol.description.contains("Test description (literal)."))
        assert(symbol.symbol.name.toString == "Doc_BothLiterals")
      }

    }

  }

}
