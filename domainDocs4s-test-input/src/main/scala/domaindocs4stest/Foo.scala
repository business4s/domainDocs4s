package domaindocs4stest

import domaindocs4s.*
import domaindocs4s.java.domainDoc

@domaindocs4s.java.domainDoc("my doc java")
@domaindocs4s.domainDoc("My class")
class Foo {

  @domainDoc("My method")
  def foo(): Unit = ()

  @domainDoc("My field")
  val bar: Unit = ()

}

object Foo extends DomainDocTrait{

  @domainDoc("My opaque type def")
  opaque type MyType1 = 1 | 2

  @domainDoc("My simple type def")
  type MyType2 = 1 | 2

  override def doc: String = "my trait doc"
}
