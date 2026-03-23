package domaindocs4s.viewercy

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.*

object Main {

  @JSExportTopLevel("main")
  def main(): Unit = {
    val jsonStr = js.Dynamic.global.window.__LINEAGE_DATA__.asInstanceOf[String]

    MultiServiceData.parse(jsonStr) match {
      case Left(singleData) =>
        // Single service mode — same behavior as before
        renderSingleService(singleData)

      case Right(multi) =>
        // Multi-service mode
        renderMultiService(multi)
    }
  }

  private def renderSingleService(data: LineageData): Unit = {
    val codeViews   = ViewStore.fromCodeViews(data)
    val defaultView = if (codeViews.nonEmpty) codeViews.head else ViewStore.connectedOnlyView(data)
    val allClassIds = data.classes.map(_.classId).toSet
    val visibleClassIds = allClassIds -- defaultView.hiddenNodeIds

    val graph = GraphBuilder.buildClassLevel(data, visibleClassIds)

    val controlsContainer = dom.document.getElementById("controls")
    val _ = render(controlsContainer, ViewerControls.component(graph, data, defaultView, codeViews))
  }

  private def renderMultiService(multi: MultiServiceData): Unit = {
    val controlsContainer = dom.document.getElementById("controls")
    val _ = render(controlsContainer, ViewerControls.multiServiceComponent(multi))
  }
}
