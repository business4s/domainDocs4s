package domaindocs4s.output.glossary

import scala.math.Ordering.Implicits.given

case class Entry(parent: Option[Entry], name: String, description: Option[String]) {

  def asSeq: Seq[String] = Seq(parent.map(_.name).getOrElse(""), name, description.getOrElse(""))

  def path: List[String] = parent.map(_.path).getOrElse(List()) :+ name

}

object Entry {

  given Ordering[Entry] = Ordering.by(_.path)

}
