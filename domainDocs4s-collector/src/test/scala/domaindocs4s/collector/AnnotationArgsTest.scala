package domaindocs4s.collector

import domaindocs4s.errors.DomainDocsArgError
import domaindocs4s.utils.TestClasspath
import org.scalatest.freespec.AnyFreeSpec
import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders
import org.scalatest.matchers.should.Matchers.*

class AnnotationArgsTest extends AnyFreeSpec {

  given ctx: Context = Context.initialize(ClasspathLoaders.read(TestClasspath.current))

  private val collector = new TastyQueryCollector

  "collector" - {

    "handles missing args and constant args" - {

      val docs = collector.collectSymbols("domaindocs4s.collector.fixtures.annotationargs.constant")

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

    "throws DomainDocsArgError on non-constant args" - {

      "non-constant description arg" in {
        val pkg = "domaindocs4s.collector.fixtures.annotationargs.nonconstantdescription"
        assertThrows[DomainDocsArgError](collector.collectSymbols(pkg))
      }

      "non-constant name arg" in {
        val pkg = "domaindocs4s.collector.fixtures.annotationargs.nonconstantname"
        assertThrows[DomainDocsArgError](collector.collectSymbols(pkg))
      }

    }

  }

}
