package domaindocs4s.viewercy

import scala.scalajs.js
import scala.scalajs.js.JSON
import org.scalajs.dom

/** A named view that controls which elements are hidden. */
case class View(
    name: String,
    hiddenNodeIds: Set[String],
)

/** Manages views: load/save from localStorage, export/import as JSON. */
object ViewStore {
  private val storageKey = "domainDocs4s-views"

  def loadAll(): List[View] = {
    val raw = dom.window.localStorage.getItem(storageKey)
    if (raw == null || raw.isEmpty) Nil
    else parseViews(raw)
  }

  def saveAll(views: List[View]): Unit = {
    dom.window.localStorage.setItem(storageKey, renderViews(views))
  }

  def exportJson(views: List[View]): String = renderViews(views)

  def importJson(json: String): List[View] = parseViews(json)

  /** Convert code-defined ViewDefinitions to viewer Views using class ID mapping. */
  def fromCodeViews(data: LineageData): List[View] = {
    val classIdMap: Map[(String, String), String] =
      data.classes.map(c => (c.packageName, c.name) -> c.classId).toMap
    data.views.map { v =>
      val hiddenNodeIds = v.hiddenClasses.flatMap(classIdMap.get)
      View(v.name, hiddenNodeIds)
    }
  }

  /** Compute the "Connected only" view — hides classes without call graph or integration edges. */
  def connectedOnlyView(data: LineageData): View = {
    val connectedClassIds = data.classes.filter { cls =>
      cls.methods.exists { m =>
        data.callGraph.exists(e => e.caller == m.ref || e.callee == m.ref) ||
        data.integrations.exists(i =>
          i.method.packageName == m.ref.packageName &&
            i.method.className == m.ref.className &&
            i.method.methodName == m.ref.methodName,
        )
      }
    }.map(_.classId).toSet

    val allClassIds = data.classes.map(_.classId).toSet
    val hidden = allClassIds -- connectedClassIds
    View("Connected only", hidden)
  }

  private def renderViews(views: List[View]): String = {
    val arr = views.map { v =>
      js.Dynamic.literal(
        name = v.name,
        hiddenNodeIds = js.Array(v.hiddenNodeIds.toSeq*),
      )
    }
    JSON.stringify(js.Array(arr*))
  }

  private def parseViews(json: String): List[View] = {
    try {
      val arr = JSON.parse(json).asInstanceOf[js.Array[js.Dynamic]]
      arr.map { v =>
        val name = v.name.asInstanceOf[String]
        val hidden = v.hiddenNodeIds.asInstanceOf[js.Array[String]].toSet
        View(name, hidden)
      }.toList
    } catch {
      case _: Exception => Nil
    }
  }
}
