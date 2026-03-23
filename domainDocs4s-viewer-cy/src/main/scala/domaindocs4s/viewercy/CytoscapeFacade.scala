package domaindocs4s.viewercy

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Minimal Cytoscape.js facade. */
object CytoscapeFacade {

  @js.native @JSImport("cytoscape", JSImport.Default)
  object CytoscapeJS extends js.Object {
    def apply(options: js.Object): CyInstance = js.native
    def use(ext: js.Any): Unit = js.native
  }

  def cytoscape(options: js.Object): CyInstance = CytoscapeJS(options)

  // Layout extensions
  @js.native @JSImport("cytoscape-fcose", JSImport.Default)
  def cytoscapeFcose: js.Any = js.native

  @js.native @JSImport("cytoscape-elk", JSImport.Default)
  def cytoscapeElk: js.Any = js.native

  @js.native @JSImport("cytoscape-dagre", JSImport.Default)
  def cytoscapeDagre: js.Any = js.native

  @js.native @JSImport("cytoscape-cose-bilkent", JSImport.Default)
  def cytoscapeCoseBilkent: js.Any = js.native

  @js.native @JSImport("elk-routed", JSImport.Default)
  def cytoscapeElkRouted: js.Any = js.native

  /** Register all layout extensions. */
  def registerExtensions(): Unit = {
    CytoscapeJS.use(cytoscapeFcose)
    CytoscapeJS.use(cytoscapeElk)
    CytoscapeJS.use(cytoscapeDagre)
    CytoscapeJS.use(cytoscapeCoseBilkent)
    CytoscapeJS.use(cytoscapeElkRouted)
  }

  // Cytoscape instance
  @js.native trait CyInstance extends js.Object {
    def add(eleObj: js.Object): CyCollection = js.native
    def add(eles: js.Array[js.Object]): CyCollection = js.native
    def remove(selector: String): CyCollection = js.native
    def layout(options: js.Object): CyLayout = js.native
    def nodes(selector: String): CyCollection = js.native
    def edges(selector: String): CyCollection = js.native
    def nodes(): CyCollection = js.native
    def edges(): CyCollection = js.native
    def elements(): CyCollection = js.native
    def on(events: String, handler: js.Function1[js.Dynamic, Unit]): Unit = js.native
    def on(events: String, selector: String, handler: js.Function1[js.Dynamic, Unit]): Unit = js.native
    def fit(padding: Int): Unit = js.native
    def fit(): Unit = js.native
    def zoom(): Double = js.native
    def zoom(level: Double): CyInstance = js.native
    def pan(): js.Dynamic = js.native
    def center(): CyInstance = js.native
    def resize(): CyInstance = js.native
    def style(): js.Dynamic = js.native
    def batch(fn: js.Function0[Unit]): Unit = js.native
    def getElementById(id: String): CyElement = js.native
    def `$`(selector: String): CyCollection = js.native
    def destroy(): Unit = js.native
  }

  @js.native trait CyLayout extends js.Object {
    def run(): CyLayout = js.native
    def on(event: String, handler: js.Function0[Unit]): CyLayout = js.native
    def promiseOn(event: String): js.Promise[js.Any] = js.native
  }

  @js.native trait CyCollection extends js.Object {
    def length: Int = js.native
    def forEach(fn: js.Function1[CyElement, Unit]): Unit = js.native
    def on(events: String, handler: js.Function1[js.Dynamic, Unit]): Unit = js.native
    def hide(): CyCollection = js.native
    def show(): CyCollection = js.native
    def remove(): CyCollection = js.native
    def restore(): CyCollection = js.native
    def addClass(cls: String): CyCollection = js.native
    def removeClass(cls: String): CyCollection = js.native
    def hasClass(cls: String): Boolean = js.native
    def filter(selector: String): CyCollection = js.native
    def connectedEdges(): CyCollection = js.native
    def children(): CyCollection = js.native
    def descendants(): CyCollection = js.native
    def parent(): CyCollection = js.native
    def sources(): CyCollection = js.native
    def targets(): CyCollection = js.native
    def data(key: String): js.Any = js.native
    def layout(options: js.Object): CyLayout = js.native
  }

  @js.native trait CyElement extends js.Object {
    def id(): String = js.native
    def data(key: String): js.Any = js.native
    def data(key: String, value: js.Any): Unit = js.native
    def isNode(): Boolean = js.native
    def isEdge(): Boolean = js.native
    def isParent(): Boolean = js.native
    def children(): CyCollection = js.native
    def descendants(): CyCollection = js.native
    def connectedEdges(): CyCollection = js.native
    def addClass(cls: String): CyElement = js.native
    def removeClass(cls: String): CyElement = js.native
    def hasClass(cls: String): Boolean = js.native
    def hide(): CyElement = js.native
    def show(): CyElement = js.native
    def position(): js.Dynamic = js.native
    def style(name: String): String = js.native
    def style(name: String, value: String): CyElement = js.native
    def classes(): js.Array[String] = js.native
  }
}
