package domaindocs4s.utils

import org.scalatest.Assertions

import java.nio.file.{Files, Paths}

object SnapshotTest {

  private val testResourcesPath = Paths
    .get(getClass.getResource("/").toURI) // domainDocs4s-examples/target/scala-3.5.1/test-classes
    .getParent                            // domainDocs4s-examples/target/scala-3.5.1
    .getParent                            // domainDocs4s-examples/target
    .getParent                            // domainDocs4s-examples
    .resolve("src/test/resources")

  def testSnapshot(content: String, path: String) = {
    val filePath    = testResourcesPath.resolve(path)
    val existingOpt = Option.when(Files.exists(filePath)) {
      Files.readString(testResourcesPath.resolve(path))
    }

    val isOk = existingOpt.contains(content)

    if (!isOk) {
      Files.createDirectories(filePath.getParent)
      Files.writeString(filePath, content)
      // file path is here so you can quickly navigate to the file
      Assertions.fail(s"Snapshot ${path} was not matching. A new value has been written to ${filePath}.")
    }
  }

}
