package domaindocs4s.viewercy

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.openqa.selenium.{By, WebDriver}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration

class ViewerCyE2ESpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  private var driver: WebDriver = scala.compiletime.uninitialized

  override def beforeAll(): Unit = {
    ViteServer.start()
    WebDriverManager.chromedriver().setup()
  }

  override def afterAll(): Unit = {
    ViteServer.stop()
  }

  override def beforeEach(): Unit = {
    val options = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu")
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
  }

  override def afterEach(): Unit = {
    if (driver != null) driver.quit()
  }

  private def wait(seconds: Int) = new WebDriverWait(driver, Duration.ofSeconds(seconds))

  private def waitForCytoscape(): Unit = {
    wait(15).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#cy canvas")))
  }

  "cytoscape viewer renders" - {

    "cytoscape canvas is present" in {
      driver.get(ViteServer.url)
      waitForCytoscape()
      val canvas = driver.findElement(By.cssSelector("#cy canvas"))
      canvas.isDisplayed shouldBe true
    }

    "fold/unfold buttons are present" in {
      driver.get(ViteServer.url)
      wait(15).until(ExpectedConditions.presenceOfElementLocated(By.xpath("//button[text()='Fold All']")))
      val foldBtn = driver.findElement(By.xpath("//button[text()='Fold All']"))
      val unfoldBtn = driver.findElement(By.xpath("//button[text()='Unfold All']"))
      foldBtn.isDisplayed shouldBe true
      unfoldBtn.isDisplayed shouldBe true
    }

    "no console errors" in {
      driver.get(ViteServer.url)
      waitForCytoscape()
      Thread.sleep(2000)
      val logs = driver.asInstanceOf[ChromeDriver].manage().logs().get("browser")
      val severeErrors = logs.getAll.toArray.toList
        .map(_.asInstanceOf[org.openqa.selenium.logging.LogEntry])
        .filter(_.getLevel.getName == "SEVERE")
        .filterNot(_.getMessage.contains("favicon.ico"))
      severeErrors shouldBe empty
    }
  }
}
