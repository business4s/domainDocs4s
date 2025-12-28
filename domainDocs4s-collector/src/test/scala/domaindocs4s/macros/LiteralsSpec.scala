package domaindocs4s.macros

import org.scalatest.freespec.AnyFreeSpec
import scala.compiletime.testing.*

class LiteralsSpec extends AnyFreeSpec {

  "ensureLiteral" - {

    "compiles for literal / reducible-to-literal expressions" - {

      val literal =
        typeChecks("""import domaindocs4s.macros.Literals.*
                     |ensureLiteral("literal")
                     |""".stripMargin)

      assert(literal)

      val concat =
        typeChecks("""import domaindocs4s.macros.Literals.*
                     |ensureLiteral("lit" + "eral")
                     |""".stripMargin)

      assert(concat)

      val inlineDef =
        typeChecks("""import domaindocs4s.macros.Literals.*
                     |transparent inline def literal = ensureLiteral("literal")
                     |literal
                     |""".stripMargin)

      assert(inlineDef)
    }

    "fails when expression can't be reduced to literal" - {

      val valRef = typeCheckErrors(
        """import domaindocs4s.macros.Literals.*
          |val x = "literal"
          |ensureLiteral(x)
          |""".stripMargin,
      )

      assert(valRef.nonEmpty)
      assert(valRef.map(_.message).mkString("\n").contains("Expected a literal"))

      val interpolation = typeCheckErrors(
        """import domaindocs4s.macros.Literals.*
          |ensureLiteral(s"literal")
          |""".stripMargin,
      )

      assert(interpolation.nonEmpty)
      assert(interpolation.map(_.message).mkString("\n").contains("Expected a literal"))

    }

  }
}
