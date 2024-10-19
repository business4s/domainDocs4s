package domaindocs4s.collector.tasty

import scala.quoted.*
import scala.tasty.inspector.*


class MyInspector extends Inspector {


  def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit = {
    import quotes.reflect.*
    given Printer[Tree] = Printer.TreeStructure


    case class EType(symbolName: String, scalaDocs: Term)

    // filters out annotation on fields for some reason
    def annotationTree(tree: Tree): Option[Expr[_]] =
      Option.when(tree.isExpr)(tree.asExpr)
        //.filter(_.isExprOf[domaindocs4s.domainDoc]).map(_.asExprOf[domaindocs4s.domainDoc])

    object TypeTraverser extends TreeAccumulator[List[EType]] {
      def foldTree(existing: List[EType], tree: Tree)(owner: Symbol): List[EType] = {
//        println(tree.show)
        val annotation = tree.symbol.annotations.flatMap(annotationTree).headOption
        if(annotation.isDefined) {
          println(tree.symbol.name)
          println(tree.show)
          println(annotation.get.show)
          println(annotation.get.asTerm.show)
          println()
        }
        foldOverTree(List(), tree)(owner)
      }
    }
    for tasty <- tastys do
      val tree = tasty.ast
      val results = TypeTraverser.foldTree(Nil, tree)(tree.symbol)
      println(results)
  }
}
