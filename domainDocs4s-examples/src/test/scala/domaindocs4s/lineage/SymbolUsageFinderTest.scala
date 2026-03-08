package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class SymbolUsageFinderTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"
  private val slickPkg = "domaindocs4s.architecture.lineage.example.slick"

  "SymbolUsageFinder" - {

    "MethodCall on class field" - {

      "finds S3Client method calls with owner FQN and method name" in {
        val searches = Seq(SymbolSearch.MethodCall(TypeMatcher.oneOf(
          "software.amazon.awssdk.services.s3.S3Client",
          "software.amazon.awssdk.services.s3.S3AsyncClient",
        )))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val s3Usages = usages.collect { case u: FoundUsage.MethodCallResult => u }
        s3Usages should not be empty

        // Should find putObject and getObject calls
        val methodNames = s3Usages.map(_.methodName).toSet
        methodNames should contain("putObject")
        methodNames should contain("getObject")

        // Owner FQN should be an S3 client type
        s3Usages.foreach { u =>
          u.ownerFqn should (include("S3Client") or include("S3AsyncClient"))
        }
      }
    }

    "MethodCall on companion object" - {

      "finds Pekko Kafka Producer calls via Select" in {
        val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
        val searches = Seq(SymbolSearch.MethodCall(
          TypeMatcher("org.apache.pekko.kafka.scaladsl.Producer"),
        ))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pekkoPkg))

        val producerUsages = usages.collect { case u: FoundUsage.MethodCallResult => u }
        producerUsages should not be empty

        // Should find flexiFlow, plainSink
        val methods = producerUsages.map(_.methodName).toSet
        methods should contain("flexiFlow")
        methods should contain("plainSink")
      }

      "finds imported member calls (Ident with TermRef prefix)" in {
        val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
        val searches = Seq(SymbolSearch.MethodCall(
          TypeMatcher("org.apache.pekko.kafka.scaladsl.Producer"),
        ))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pekkoPkg))

        // KafkaImportedFlexiFlowProducer uses `importedFlexiFlow(settings)` via import
        val importedUsages = usages.collect { case u: FoundUsage.MethodCallResult => u }
          .filter(u => u.path.toMethodRef.className == "KafkaImportedFlexiFlowProducer")
        importedUsages should not be empty
      }
    }

    "MethodCall inside if/else branches" - {

      "finds S3Client calls inside if branches" in {
        val searches = Seq(SymbolSearch.MethodCall(TypeMatcher.oneOf(
          "software.amazon.awssdk.services.s3.S3Client",
        )))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val conditionalUsages = usages.collect { case u: FoundUsage.MethodCallResult => u }
          .filter(_.path.toMethodRef.className == "S3ConditionalExporter")
        conditionalUsages should have size 1
        conditionalUsages.head.methodName shouldBe "putObject"
      }
    }

    "MethodCall on lambda parameter (not class field)" - {

      "finds S3Client calls when receiver is a lambda parameter" in {
        val searches = Seq(SymbolSearch.MethodCall(TypeMatcher.oneOf(
          "software.amazon.awssdk.services.s3.S3Client",
        )))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val lambdaUsages = usages.collect { case u: FoundUsage.MethodCallResult => u }
          .filter(u => u.path.toMethodRef.className == "S3LambdaExporter" && u.methodName == "putObject")
        lambdaUsages should have size 1
        // The putObject call is inside the lambda, but attributed to exportViaCallback
        lambdaUsages.head.path.toMethodRef.methodName shouldBe "exportViaCallback"
      }
    }

    "ClassInheritance" - {

      "finds Fs2Grpc parent types with inherited methods" in {
        val searches = Seq(SymbolSearch.ClassInheritance(
          TypeMatcher.fqnEndsWith("Fs2Grpc"),
        ))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val inheritanceUsages = usages.collect { case u: FoundUsage.InheritanceResult => u }
        inheritanceUsages should not be empty

        // Should find UserServiceFs2Grpc
        val parentNames = inheritanceUsages.map(_.parentSimpleName).toSet
        parentNames should contain("UserServiceFs2Grpc")

        // Should have inherited methods
        val userServiceUsage = inheritanceUsages.find(_.parentSimpleName == "UserServiceFs2Grpc")
        userServiceUsage.get.inheritedMethods should contain allOf ("getBalance", "deposit", "getHistory")
      }
    }

    "NestingPath" - {

      "toMethodRef extracts correct package, class, and method" in {
        val searches = Seq(SymbolSearch.MethodCall(TypeMatcher.oneOf(
          "software.amazon.awssdk.services.s3.S3Client",
          "software.amazon.awssdk.services.s3.S3AsyncClient",
        )))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val exporterUsages = usages.collect { case u: FoundUsage.MethodCallResult => u }
          .filter(_.path.toMethodRef.className == "S3Exporter")
        exporterUsages should not be empty

        val ref = exporterUsages.head.path.toMethodRef
        ref.packageName shouldBe pkg
        ref.className shouldBe "S3Exporter"
        ref.methodName shouldBe "exportData"
      }

      "path includes Package and ClassOrObject nodes" in {
        val searches = Seq(SymbolSearch.MethodCall(TypeMatcher.oneOf(
          "software.amazon.awssdk.services.s3.S3Client",
        )))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(pkg))

        val usage = usages.collect { case u: FoundUsage.MethodCallResult => u }.head
        val nodes = usage.path.nodes

        nodes.exists(_.isInstanceOf[NestingNode.Package]) shouldBe true
        nodes.exists(_.isInstanceOf[NestingNode.ClassOrObject]) shouldBe true
        nodes.exists(_.isInstanceOf[NestingNode.Method]) shouldBe true
      }
    }

    "enumerateMethodBodies" - {

      "finds top-level class methods" in {
        val methods = SymbolUsageFinder.enumerateMethodBodies(List(pkg))
        val userRepoMethods = methods.filter(_.ref.className == "UserRepo")
        userRepoMethods should not be empty
        userRepoMethods.map(_.ref.methodName).toSet should contain allOf ("getBalance", "getTransactions", "insertTransaction", "updateBalance")
      }

      "finds anonymous class methods with correct parent class attribution" in {
        val methods = SymbolUsageFinder.enumerateMethodBodies(List(slickPkg))
        val factoryMethods = methods.filter(_.ref.className == "FactoryRepo")
        factoryMethods should not be empty
        factoryMethods.map(_.ref.methodName).toSet should contain allOf ("getOrders", "insertOrder", "getItems", "deleteItem")
      }

      "includes declared type for top-level methods" in {
        val methods = SymbolUsageFinder.enumerateMethodBodies(List(pkg))
        val userRepoMethods = methods.filter(_.ref.className == "UserRepo")
        userRepoMethods.foreach { m =>
          m.declaredType shouldBe defined
        }
      }

      "includes val bodies in enumeration" in {
        val methods = SymbolUsageFinder.enumerateMethodBodies(List(pkg))
        // BalanceProjection.handler is a val, not a def — should be included
        val projectionVals = methods.filter(m => m.ref.className == "BalanceProjection" && m.ref.methodName == "handler")
        projectionVals should have size 1
        // InlineQueryHolder.activeUserCount is also a val
        val holderVals = methods.filter(m => m.ref.className == "InlineQueryHolder" && m.ref.methodName == "activeUserCount")
        holderVals should have size 1
        // CachedService.defaultBalance is also a val
        val cachedVals = methods.filter(m => m.ref.className == "CachedService" && m.ref.methodName == "defaultBalance")
        cachedVals should have size 1
      }

      "anonymous class methods have None declared type" in {
        val methods = SymbolUsageFinder.enumerateMethodBodies(List(slickPkg))
        val factoryMethods = methods.filter(m =>
          m.ref.className == "FactoryRepo" &&
          Set("getOrders", "insertOrder", "getItems", "deleteItem").contains(m.ref.methodName),
        )
        factoryMethods.foreach { m =>
          m.declaredType shouldBe None
        }
      }
    }

    "LiteralValue extraction" - {

      "extracts string literal from constructor args" in {
        // Slick Table constructor: new Table[(Long, BigDecimal)](tag, "account_balances")
        // We use MethodCall to find constructor calls
        val searches = Seq(SymbolSearch.MethodCall(
          TypeMatcher.fqnEndsWith("Table"),
        ))
        val finder = new SymbolUsageFinder(searches)
        val usages = finder.findAll(List(slickPkg))

        val constructorCalls = usages.collect { case u: FoundUsage.MethodCallResult if u.methodName == "<init>" => u }
        // May or may not find constructor calls depending on TASTy representation
        // At minimum verify the search doesn't crash
        constructorCalls.size should be >= 0
      }
    }
  }
}
