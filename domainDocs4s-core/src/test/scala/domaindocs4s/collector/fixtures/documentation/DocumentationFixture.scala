package domaindocs4s.collector.fixtures.documentation

import domaindocs4s.domainDoc

@domainDoc("Example domain class for documentation tests.")
class ExampleEntity {

  @domainDoc("A documented method inside ExampleEntity.")
  def documentedMethod(): Unit = ()

  @domainDoc("A documented field inside ExampleEntity.")
  val documentedField: String = "some value"

  @domainDoc("A documented type alias inside ExampleEntity.")
  type DocumentedType = String | Int

  // Not documented: should not appear in generated documentation
  def internalHelper(): String = "hidden"

  @domainDoc("A nested object inside ExampleEntity.")
  object Nested {

    @domainDoc("A method inside Nested object.")
    def nestedMethod(): String = "nested"
  }

}

@domainDoc("Companion object of ExampleEntity.")
object ExampleEntity {

  @domainDoc("A documented value inside the companion object.")
  val companionVal: Int = 42

  // Not documented: should not appear in generated documentation
  val rawVal: Int = 0
}

@domainDoc("Standalone utility object.")
object UtilityObject {

  @domainDoc("A documented function inside UtilityObject.")
  def utilFunction(x: Int): Int = x + 1
}

@domainDoc("An example enumeration.")
enum ExampleStatus {
  @domainDoc("Indicates entity is active.") case Active

  @domainDoc("Indicates entity is inactive.") case Inactive
}
