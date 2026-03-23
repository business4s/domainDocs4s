package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.ExampleTransactors
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TransactorTrackerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val transactorMapping = TransactorMapping.builder
    .source[ExampleTransactors.PrimaryXa](database = "primary_db", schema = Some("primary_schema"))
    .source[ExampleTransactors.SecondaryXa](database = "secondary_db")
    .build

  private val doobieWithTracking = new TastyDoobieScanner(transactorMapping = transactorMapping).scan(List(pkg))
  private val doobieWithout      = new TastyDoobieScanner().scan(List(pkg))

  "TransactorTracker via TastyDoobieScanner" - {

    "enriches TypedTransactorRepo integrations with PrimaryXa database and schema" in {
      val typedRepoIntegrations = doobieWithTracking.filter(_.method.className == "TypedTransactorRepo")
      typedRepoIntegrations should not be empty
      typedRepoIntegrations.foreach { di =>
        di.resourceId.segments should contain(("database", "primary_db"))
        di.resourceId.segments should contain(("schema", "primary_schema"))
      }
    }

    "enriches SecondaryRepo integrations with SecondaryXa database" in {
      val secondaryIntegrations = doobieWithTracking.filter(_.method.className == "SecondaryRepo")
      secondaryIntegrations should not be empty
      secondaryIntegrations.foreach { di =>
        di.resourceId.segments should contain(("database", "secondary_db"))
        // SecondaryXa has no schema configured
        di.resourceId.segments.map(_._1) should not contain "schema"
      }
    }

    "does not enrich untyped repos (e.g. UserRepo with plain Transactor[IO])" in {
      val userRepoWithTracking = doobieWithTracking.filter(_.method.className == "UserRepo")
      val userRepoWithout      = doobieWithout.filter(_.method.className == "UserRepo")
      // UserRepo uses plain Transactor[IO] (not a typed transactor), so segments should be unchanged
      userRepoWithTracking.map(_.resourceId) shouldBe userRepoWithout.map(_.resourceId)
    }

    "without transactorMapping, TypedTransactorRepo has no database segment" in {
      val typedRepoNoMapping = doobieWithout.filter(_.method.className == "TypedTransactorRepo")
      typedRepoNoMapping should not be empty
      typedRepoNoMapping.foreach { di =>
        di.resourceId.segments.map(_._1) should not contain "database"
      }
    }

    "different typed transactors map to different databases" in {
      val primaryDb   = doobieWithTracking.filter(_.method.className == "TypedTransactorRepo").map(_.resourceId)
      val secondaryDb = doobieWithTracking.filter(_.method.className == "SecondaryRepo").map(_.resourceId)

      primaryDb.flatMap(_.segments.find(_._1 == "database").map(_._2)).toSet shouldBe Set("primary_db")
      secondaryDb.flatMap(_.segments.find(_._1 == "database").map(_._2)).toSet shouldBe Set("secondary_db")
    }
  }

  "TransactorTracker directly" - {

    "returns empty map when mapping is empty" in {
      val tracker = new TransactorTracker(TransactorMapping.empty)
      val result  = tracker.scan(List(pkg), Nil, Nil)
      result shouldBe empty
    }

    "maps classes by field type" in {
      val tracker = new TransactorTracker(transactorMapping)
      val result  = tracker.scan(List(pkg), Nil, doobieWithout)
      result should contain key (pkg, "TypedTransactorRepo")
      result((pkg, "TypedTransactorRepo")).database shouldBe Some("primary_db")
      result((pkg, "TypedTransactorRepo")).schema shouldBe Some("primary_schema")

      result should contain key (pkg, "SecondaryRepo")
      result((pkg, "SecondaryRepo")).database shouldBe Some("secondary_db")
    }

    "does not map classes without matching transactor types" in {
      val tracker = new TransactorTracker(transactorMapping)
      val result  = tracker.scan(List(pkg), Nil, doobieWithout)
      result should not contain key(pkg, "UserRepo")
      result should not contain key(pkg, "FragmentRepo")
    }
  }

  "DbSegments.applyTo" - {

    "fills in missing segments without overriding existing ones" in {
      val segs = DbSegments(database = Some("db1"), schema = Some("schema1"))
      val rid  = ResourceId.DbTable("my_table")
      segs.applyTo(rid) shouldBe ResourceId.DbTable("my_table", database = Some("db1"), schema = Some("schema1"))
    }

    "preserves existing segments on the ResourceId" in {
      val segs = DbSegments(database = Some("db1"), schema = Some("schema1"))
      val rid  = ResourceId.DbTable("my_table", database = Some("existing_db"), schema = Some("existing_schema"))
      segs.applyTo(rid) shouldBe ResourceId.DbTable("my_table", database = Some("existing_db"), schema = Some("existing_schema"))
    }

    "fills only missing segments" in {
      val segs = DbSegments(database = Some("db1"), schema = Some("schema1"), cluster = Some("cluster1"))
      val rid  = ResourceId.DbTable("my_table", database = Some("existing_db"))
      val result = segs.applyTo(rid)
      result.database shouldBe Some("existing_db")
      result.schema shouldBe Some("schema1")
      result.cluster shouldBe Some("cluster1")
    }
  }
}
