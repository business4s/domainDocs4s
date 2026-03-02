package domaindocs4s.architecture

import scala.reflect.ClassTag

// ============================================================================
// Stub types for the architecture examples.
//
// These represent the planned API surface of domaindocs4s.arch.*
// Once the library module is implemented, these stubs will be replaced by
// imports from the real library.
// ============================================================================

// ── Node categories (fixed, drives visual encoding) ─────────────────────────

enum NodeCategory {
  case Api
  case Message
  case Dataset
  case Compute
}

// ── Artifact (open trait, user-extensible) ──────────────────────────────────

trait Artifact {
  def category: NodeCategory
  def matchKey: String
}

// Library-provided artifacts
object Artifacts {
  // Api
  case class GrpcEndpoint(serviceName: String, methodName: String) extends Artifact {
    val category = NodeCategory.Api
    val matchKey = s"grpc:$serviceName/$methodName"
  }
  case class HttpEndpoint(method: String, path: String) extends Artifact {
    val category = NodeCategory.Api
    val matchKey = s"http:$method:$path"
  }

  // Message
  case class KafkaTopic(topicName: String) extends Artifact {
    val category = NodeCategory.Message
    val matchKey = s"kafka:$topicName"
  }

  // Dataset
  case class DatabaseTable(database: String, schema: String, tableName: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"db:$database.$schema.$tableName"
  }
  case class S3Location(bucket: String, prefix: String = "") extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"s3:$bucket/$prefix"
  }
  case class Spreadsheet(provider: String, id: String = "") extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"sheet:$provider:$id"
  }
  case class DataWarehouse(system: String, table: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"dw:$system:$table"
  }
  case class Journal(name: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"journal:$name"
  }

  // Compute
  case class Component(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"component:$name"
  }
  case class Projection(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"projection:$name"
  }
  case class Job(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"job:$name"
  }
  case class Cache(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"cache:$name"
  }
}

// ── Core model ──────────────────────────────────────────────────────────────

case class Node(label: String, artifact: Artifact, internal: Boolean = true) {
  def id: String = artifact.matchKey
  def exposed: Node = copy(internal = false)
}

enum EdgeType {
  case Produces
  case Consumes
}

case class Edge(from: Node, edgeType: EdgeType, to: Node, label: String = "")

case class Subgraph(label: String, nodes: List[Node])

case class FlowChart(
    edges: List[Edge],
    subgraphs: List[Subgraph] = Nil,
) {
  lazy val nodes: List[Node] = {
    val seen   = scala.collection.mutable.LinkedHashSet.empty[String]
    val result = List.newBuilder[Node]
    for {
      edge <- edges
      n    <- List(edge.from, edge.to)
      if seen.add(n.id)
    } result += n
    result.result()
  }

  def view(name: String): ViewBuilder = ViewBuilder(name, this)
}

// ── DSL ─────────────────────────────────────────────────────────────────────

import Artifacts.*

def component(name: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else name, Component(name))

def component[T](using ct: ClassTag[T]): Node = {
  val name = ct.runtimeClass.getSimpleName.stripSuffix("$")
  Node(splitCamelCase(name), Component(name))
}

def component[T](label: String)(using ct: ClassTag[T]): Node = {
  val name = ct.runtimeClass.getSimpleName.stripSuffix("$")
  Node(label, Component(name))
}

def kafkaTopic(topicName: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else topicName, KafkaTopic(topicName))

def dbTable(database: String, schema: String, tableName: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else tableName, DatabaseTable(database, schema, tableName))

def grpcEndpoint(serviceName: String, methodName: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else s"$serviceName/$methodName", GrpcEndpoint(serviceName, methodName))

def httpEndpoint(method: String, path: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else s"$method $path", HttpEndpoint(method, path))

def s3Location(bucket: String, prefix: String = "", label: String = ""): Node =
  Node(if (label.nonEmpty) label else s"S3: $bucket/$prefix", S3Location(bucket, prefix))

def spreadsheet(provider: String, id: String = "", label: String = ""): Node =
  Node(if (label.nonEmpty) label else provider, Spreadsheet(provider, id))

def dataWarehouse(system: String, table: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else s"$system: $table", DataWarehouse(system, table))

def journal(name: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else name, Journal(name))

def cache(name: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else name, Cache(name))

def projection(name: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else name, Projection(name))

def job(name: String, label: String = ""): Node =
  Node(if (label.nonEmpty) label else name, Job(name))

def subgraph(label: String)(nodes: Node*): Subgraph =
  Subgraph(label, nodes.toList)

private def splitCamelCase(name: String): String =
  name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")

// ── Edge DSL ────────────────────────────────────────────────────────────────

extension (n: Node) {
  infix def produces(to: Node): Edge = Edge(n, EdgeType.Produces, to)
  infix def consumes(to: Node): Edge = Edge(n, EdgeType.Consumes, to)
}

extension (e: Edge) {
  infix def label(text: String): Edge = e.copy(label = text)
}

// ── View builder ────────────────────────────────────────────────────────────

case class ViewBuilder(name: String, source: FlowChart, includeIds: Option[Set[String]] = None, excludeIds: Set[String] = Set.empty) {

  def only(nodes: Node*): ViewBuilder =
    copy(includeIds = Some(nodes.map(_.id).toSet))

  def exclude(nodes: Node*): ViewBuilder =
    copy(excludeIds = excludeIds ++ nodes.map(_.id))

  def build: FlowChart = {
    val allowedIds = includeIds.getOrElse(source.nodes.map(_.id).toSet) -- excludeIds
    val filteredEdges = source.edges.filter(e => allowedIds.contains(e.from.id) && allowedIds.contains(e.to.id))
    val filteredSubgraphs = source.subgraphs.map(sg => sg.copy(nodes = sg.nodes.filter(n => allowedIds.contains(n.id)))).filter(_.nodes.nonEmpty)
    FlowChart(filteredEdges, filteredSubgraphs)
  }
}
