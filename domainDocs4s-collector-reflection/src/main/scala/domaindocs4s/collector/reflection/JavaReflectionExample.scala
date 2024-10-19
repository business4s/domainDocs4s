package domaindocs4s.collector.reflection

import domaindocs4stest.Foo

object JavaReflectionExample {

  def main(args: Array[String]): Unit = {
    val classes: List[String] = List("domaindocs4stest.Foo") // get from build phase
    classes.foreach(className => {
      val clz = Thread.currentThread().getContextClassLoader.loadClass(className)
      clz.getAnnotations
        .collect({ case x: domaindocs4s.java.domainDoc => x })
        .foreach(x => println(x.description()))
    })

    val myPackage = classOf[Foo].getPackage
    val myPackageAnnotations = myPackage.getAnnotations
    System.out.println("Available annotations for package : " + myPackage.getName)
    for (a <- myPackageAnnotations) {
      System.out.println("\t * " + a)
    }

  }
}
