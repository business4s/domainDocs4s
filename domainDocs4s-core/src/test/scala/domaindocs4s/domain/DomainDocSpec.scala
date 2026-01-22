package domaindocs4s.domain

import org.scalatest.freespec.AnyFreeSpec

import scala.compiletime.testing.*

class DomainDocSpec extends AnyFreeSpec {

  "domainDoc" - {

    "compiles for literals and reducible-to-literal expressions" in {

      val literal =
        typeChecks("""import domaindocs4s.domainDoc
                     |@domainDoc("literal")
                     |case class Domain(value: String)
                     |""".stripMargin)

      assert(literal)

      val concat =
        typeChecks("""import domaindocs4s.domainDoc
                     |@domainDoc("lit" + "eral")
                     |case class Domain(value: String)
                     |""".stripMargin)

      assert(concat)

      val block =
        typeChecks("""import domaindocs4s.domainDoc
                     |@domainDoc({ 1 + 2; "literal" })
                     |case class Domain(value: String)
                     |""".stripMargin)

      assert(block)

      val inlineDef =
        typeChecks("""import domaindocs4s.domainDoc
                     |transparent inline def literal = "literal"
                     |@domainDoc(literal)
                     |case class Domain(value: String)
                     |""".stripMargin)

      assert(inlineDef)

    }

    "doesn't compile for expression that can't be reduced to literal" in {

      val valRef = typeCheckErrors(
        """import domaindocs4s.domainDoc
          |val x: String = "literal"
          |@domainDoc(x)
          |case class Domain(value: String)
          |""".stripMargin,
      )

      assert(valRef.nonEmpty)
      assert(valRef.map(_.message).mkString("\n").contains("Required: domaindocs4s.domain.LiteralString | Null"))

      val interpolation = typeCheckErrors(
        """import domaindocs4s.domainDoc
          |@domainDoc(s"literal")
          |case class Domain(value: String)
          |""".stripMargin,
      )

      assert(interpolation.nonEmpty)
      assert(interpolation.map(_.message).mkString("\n").contains("Required: domaindocs4s.domain.LiteralString | Null"))

    }

  }
}
