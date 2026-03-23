package domaindocs4s.architecture.lineage

import domaindocs4s.macros.TypeFqnMacro

/** Database segments to apply when a transactor source is matched.
  *
  * These fill in missing segments on `ResourceId.DbTable` discovered by doobie/slick scanners. Existing segments on the ResourceId are NOT overridden.
  */
case class DbSegments(
    database: Option[String] = None,
    schema: Option[String] = None,
    cluster: Option[String] = None,
) {

  def applyTo(rid: ResourceId.DbTable): ResourceId.DbTable =
    rid.copy(
      database = rid.database.orElse(database),
      schema = rid.schema.orElse(schema),
      cluster = rid.cluster.orElse(cluster),
    )
}

object DbSegments {
  def apply(database: String): DbSegments =
    DbSegments(database = Some(database))
}

/** Transactor-to-database mapping for automatic database attribution.
  *
  * Two mechanisms:
  *   - `byTypeFqn`: matches field/arg types against registered type FQNs (compile-time safe via macro)
  *   - `byName`: matches constructor arg variable names (scan-time verified — scan fails if a name is never matched)
  */
case class TransactorMapping(
    byTypeFqn: Map[String, DbSegments] = Map.empty,
    byName: Map[String, DbSegments] = Map.empty,
) {
  def isEmpty: Boolean = byTypeFqn.isEmpty && byName.isEmpty
}

object TransactorMapping {
  val empty: TransactorMapping = TransactorMapping()

  def builder: Builder = new Builder

  class Builder {
    private val _byType = scala.collection.mutable.Map.empty[String, DbSegments]
    private val _byName = scala.collection.mutable.Map.empty[String, DbSegments]

    /** Register a transactor type with its database segments. Compile-time safe: T must be a valid type.
      *
      * Works with opaque types, type aliases, classes — anything the macro can resolve.
      *
      * {{{
      * .source[RedshiftXa](database = "redshift")
      * .source[OperationalXa](database = "operational", schema = Some("public"))
      * }}}
      */
    inline def source[T](
        database: String,
        schema: Option[String] = None,
        cluster: Option[String] = None,
    ): Builder = {
      _byType += TypeFqnMacro.fqn[T] -> DbSegments(Some(database), schema, cluster)
      this
    }

    /** Register a variable name with its database segments. Scan-time verified: the scan fails if this name never appears as a constructor argument.
      *
      * Use this as a fallback when type-based matching is not feasible (e.g., for local variables in for-comprehensions that cannot be typed).
      *
      * Matching: exact match on the name, or prefix match with `.` separator (e.g., "transactors" matches "transactors.writer").
      */
    def name(
        varName: String,
        database: String,
        schema: Option[String] = None,
        cluster: Option[String] = None,
    ): Builder = {
      _byName += varName -> DbSegments(Some(database), schema, cluster)
      this
    }

    def build: TransactorMapping = TransactorMapping(_byType.toMap, _byName.toMap)
  }
}
