package domaindocs4stest

import domaindocs4s.*

@domaindocs4s.domainDoc("My class")
class Foo {

  @domainDoc("My method")
  def foo(): Unit = ()

  @domainDoc("My field")
  val bar: Unit = ()

  @domainDoc(name = "field with just name")
  val bar1: Unit = ()

}

object Foo {

  @domainDoc("My opaque type def")
  opaque type MyType1 = 1 | 2

  @domainDoc("My simple type def")
  type MyType2 = 1 | 2

}
