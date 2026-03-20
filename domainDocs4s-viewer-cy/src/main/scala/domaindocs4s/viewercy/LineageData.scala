package domaindocs4s.viewercy

import scala.scalajs.js
import scala.scalajs.js.JSON

/** Parsed lineage data model — mirrors the JSON format from JsonRenderer. */
case class MethodRef(packageName: String, className: String, methodName: String) {
  def id: String      = s"${packageName.hashCode.abs}_${className}_$methodName"
  def classId: String = s"cls_${packageName.hashCode.abs}_$className"
  def display: String = s"$className.$methodName"
}

case class MethodInfo(
    ref: MethodRef,
    directAccess: String,
    effectiveAccess: String,
)

case class Segment(level: String, value: String)

case class ClassInfo(
    name: String,
    packageName: String,
    displayName: String,
    group: Option[String],
    effectiveAccess: String,
    methods: List[MethodInfo],
) {
  def classId: String = s"cls_${packageName.hashCode.abs}_$name"
}

case class CallEdge(caller: MethodRef, callee: MethodRef)

case class Integration(
    method: MethodRef,
    accessType: String,
    resourceType: String,
    scanner: String,
    target: String,
    resourceKey: String,
    evidence: String,
    segments: List[Segment],
)

case class ResourceDiscovery(
    method: MethodRef,
    accessType: String,
    scanner: String,
    evidence: String,
)

case class Resource(
    key: String,
    target: String,
    resourceType: String,
    segments: List[Segment],
    discoveries: List[ResourceDiscovery] = Nil,
)

case class ResourceDependency(
    from: String,
    to: String,
    resourceType: String,
    label: String,
)

case class LineageChain(
    entryPoint: MethodRef,
    path: List[MethodRef],
    target: String,
    resourceType: String,
    accessType: String,
)

case class ResourceTypeDisplayConfig(
    containerLabel: Option[String],
    foldAtLevel: Option[String],
)

case class ViewDefinition(
    name: String,
    hiddenClasses: Set[(String, String)],
)

case class LineageData(
    classes: List[ClassInfo],
    callGraph: List[CallEdge],
    integrations: List[Integration],
    resources: List[Resource],
    resourceDependencies: List[ResourceDependency],
    lineageChains: List[LineageChain],
    views: List[ViewDefinition] = Nil,
    resourceDisplayConfig: Map[String, ResourceTypeDisplayConfig] = Map.empty,
    segmentLabels: Map[String, String] = Map.empty,
)

case class ServiceEntry(name: String, data: LineageData)

case class MultiServiceData(services: List[ServiceEntry])

object MultiServiceData {
  /** Parse JSON that is either a single LineageData or {"services": [...]}. */
  def parse(jsonStr: String): Either[LineageData, MultiServiceData] = {
    val raw = js.JSON.parse(jsonStr).asInstanceOf[js.Dynamic]
    val servicesField = raw.services
    if (js.isUndefined(servicesField) || servicesField == null) {
      Left(LineageData.parse(jsonStr))
    } else {
      val arr = servicesField.asInstanceOf[js.Array[js.Dynamic]]
      val entries = arr.map { entry =>
        val name = entry.name.asInstanceOf[String]
        val dataJson = js.JSON.stringify(entry.data)
        ServiceEntry(name, LineageData.parse(dataJson))
      }.toList
      Right(MultiServiceData(entries))
    }
  }
}

object LineageData {
  def parse(jsonStr: String): LineageData = {
    val raw = JSON.parse(jsonStr).asInstanceOf[js.Dynamic]

    def str(v: js.Dynamic): String              = if (js.isUndefined(v) || v == null) "" else v.asInstanceOf[String]
    def optStr(v: js.Dynamic): Option[String]    = if (js.isUndefined(v) || v == null) None else Some(v.asInstanceOf[String])
    def arr(v: js.Dynamic): js.Array[js.Dynamic] = if (js.isUndefined(v) || v == null) js.Array() else v.asInstanceOf[js.Array[js.Dynamic]]

    def parseRef(r: js.Dynamic): MethodRef =
      MethodRef(str(r.packageName), str(r.className), str(r.methodName))

    def parseSegments(v: js.Dynamic): List[Segment] =
      arr(v).map(s => Segment(str(s.level), str(s.value))).toList

    val classes = arr(raw.classes).map { c =>
      ClassInfo(
        name = str(c.name),
        packageName = str(c.packageName),
        displayName = str(c.displayName),
        group = optStr(c.group),
        effectiveAccess = str(c.effectiveAccess),
        methods = arr(c.methods).map { m =>
          MethodInfo(
            ref = MethodRef(str(m.packageName), str(m.className), str(m.name)),
            directAccess = str(m.directAccess),
            effectiveAccess = str(m.effectiveAccess),
          )
        }.toList,
      )
    }.toList

    val callGraph = arr(raw.callGraph).map { e =>
      CallEdge(parseRef(e.caller), parseRef(e.callee))
    }.toList

    val integrations = arr(raw.integrations).map { i =>
      Integration(
        method = parseRef(i.method),
        accessType = str(i.accessType),
        resourceType = str(i.resourceType),
        scanner = str(i.scanner),
        target = str(i.target),
        resourceKey = str(i.resourceKey),
        evidence = str(i.evidence),
        segments = parseSegments(i.segments),
      )
    }.toList

    val resources = arr(raw.resources).map { r =>
      Resource(
        key = str(r.key),
        target = str(r.target),
        resourceType = str(r.resourceType),
        segments = parseSegments(r.segments),
        discoveries = arr(r.discoveries).map { d =>
          ResourceDiscovery(
            method = parseRef(d.method),
            accessType = str(d.accessType),
            scanner = str(d.scanner),
            evidence = str(d.evidence),
          )
        }.toList,
      )
    }.toList

    val resourceDeps = arr(raw.resourceDependencies).map { d =>
      ResourceDependency(
        from = str(d.from),
        to = str(d.to),
        resourceType = str(d.resourceType),
        label = str(d.label),
      )
    }.toList

    val chains = arr(raw.lineageChains).map { c =>
      val integ = c.integration
      LineageChain(
        entryPoint = parseRef(c.entryPoint),
        path = arr(c.path).map(parseRef).toList,
        target = str(integ.target),
        resourceType = str(integ.resourceType),
        accessType = str(integ.accessType),
      )
    }.toList

    val views = arr(raw.views).map { v =>
      val hidden = arr(v.hiddenClasses).map { h =>
        (str(h.packageName), str(h.className))
      }.toSet
      ViewDefinition(str(v.name), hidden)
    }.toList

    val displayConfig: Map[String, ResourceTypeDisplayConfig] = {
      val cfg = raw.resourceDisplayConfig
      if (js.isUndefined(cfg) || cfg == null) Map.empty
      else {
        val keys = js.Object.keys(cfg.asInstanceOf[js.Object]).toList
        keys.map { k =>
          val entry = cfg.selectDynamic(k)
          k -> ResourceTypeDisplayConfig(
            containerLabel = optStr(entry.containerLabel),
            foldAtLevel = optStr(entry.foldAtLevel),
          )
        }.toMap
      }
    }

    val segmentLabels: Map[String, String] = {
      val sl = raw.segmentLabels
      if (js.isUndefined(sl) || sl == null) Map.empty
      else {
        val keys = js.Object.keys(sl.asInstanceOf[js.Object]).toList
        keys.map(k => k -> str(sl.selectDynamic(k))).toMap
      }
    }

    LineageData(classes, callGraph, integrations, resources, resourceDeps, chains, views, displayConfig, segmentLabels)
  }
}
