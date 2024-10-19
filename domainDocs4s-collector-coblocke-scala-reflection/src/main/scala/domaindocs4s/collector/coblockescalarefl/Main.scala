package domaindocs4s.collector.coblockescalarefl

import co.blocke.scala_reflection.RType

object Main {

  def main(args: Array[String]): Unit = {
    val clazz = Thread.currentThread().getContextClassLoader.loadClass("domaindocs4stest.Foo$")
    println(RType.of(clazz).pretty)
    {
      val clazz = Thread.currentThread().getContextClassLoader.loadClass("domaindocs4stest.Foo$")
      println(RType.of(clazz).pretty)
    }
  }

}
