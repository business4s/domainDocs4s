package domaindocs4s.architecture.lineage
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*

class SqlUtilsTest extends AnyFreeSpec {

  "SqlUtils.extractTableRef" - {

    "extracts table from SELECT" in {
      SqlUtils.extractTableRef("SELECT * FROM users WHERE id = ?").map(_.fullName) shouldBe Some("users")
    }

    "extracts table from INSERT INTO" in {
      SqlUtils.extractTableRef("INSERT INTO orders (id) VALUES (?)").map(_.fullName) shouldBe Some("orders")
    }

    "extracts schema.table" in {
      val ref = SqlUtils.extractTableRef("SELECT * FROM public.users")
      ref.map(_.schema) shouldBe Some(Some("public"))
      ref.map(_.table) shouldBe Some("users")
    }

    "returns None when no table name can be extracted" in {
      SqlUtils.extractTableRef("TRUNCATE TABLE ,  CASCADE") shouldBe None
    }

    "returns None for empty SQL" in {
      SqlUtils.extractTableRef("") shouldBe None
    }

    "skips SQL keywords as table names" in {
      SqlUtils.extractTableRef("SELECT * FROM unnest(array[1,2])") shouldBe None
    }
  }
}
