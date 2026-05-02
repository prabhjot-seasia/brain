package com.assurant.brain.bdd.ui;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UISteps {

    private static final Logger log = LogManager.getLogger(UISteps.class);
    private static final String BASE_URL = System.getProperty("brain.ui.url", "http://localhost:3000");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    private WebDriver driver;
    private WebDriverWait wait;

    @Before("@ui")
    public void setUp() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        boolean headless = !"false".equalsIgnoreCase(System.getProperty("brain.ui.headless", "true"));
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-extensions");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");

        org.openqa.selenium.logging.LoggingPreferences logPrefs = new org.openqa.selenium.logging.LoggingPreferences();
        logPrefs.enable(org.openqa.selenium.logging.LogType.BROWSER, java.util.logging.Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, WAIT_TIMEOUT);
    }

    @After("@ui")
    public void tearDown(io.cucumber.java.Scenario scenario) {
        AssertionError consoleViolation = null;
        if (driver != null) {
            if (!scenario.getSourceTagNames().contains("@allow-server-errors")) {
                try {
                    consoleViolation = assertNoServerErrorsInConsole();
                } catch (RuntimeException ex) {
                    log.warn("Could not read browser console for clean-check: {}", ex.getMessage());
                }
            }
            if ((scenario.isFailed() || consoleViolation != null) && driver instanceof TakesScreenshot) {
                try {
                    File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    Path dest = Path.of("build", "screenshots", scenario.getName().replaceAll("[^a-zA-Z0-9]", "_") + ".png");
                    Files.createDirectories(dest.getParent());
                    Files.copy(screenshot.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("Screenshot saved: {}", dest);
                    log.debug("Page source on failure:\n{}", driver.getPageSource().substring(0, Math.min(2000, driver.getPageSource().length())));
                } catch (Exception e) {
                    log.warn("Failed to capture screenshot: {}", e.getMessage());
                }
            }
            driver.quit();
        }
        if (consoleViolation != null) throw consoleViolation;
    }

    private AssertionError assertNoServerErrorsInConsole() {
        org.openqa.selenium.logging.LogEntries entries =
                driver.manage().logs().get(org.openqa.selenium.logging.LogType.BROWSER);
        StringBuilder bad = new StringBuilder();
        for (org.openqa.selenium.logging.LogEntry e : entries) {
            String msg = e.getMessage();
            if (msg.contains(" 500 ") || msg.contains(" 500)")
                    || msg.contains(" 502 ") || msg.contains(" 503 ")) {
                bad.append("\n  ").append(e.getLevel()).append(" ").append(msg);
            }
        }
        if (bad.length() == 0) return null;
        return new AssertionError("Browser console saw server-error response(s):" + bad);
    }

    @Given("the Brain UI is running on the configured base URL")
    public void brainUiIsRunning() {
    }

    @When("I open the Brain UI home page")
    public void openHomePage() {
        driver.get(BASE_URL);
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("header")));
    }

    private static final java.util.Map<String, String> PAGE_PATHS = java.util.Map.ofEntries(
            java.util.Map.entry("Projects", "/projects"),
            java.util.Map.entry("Ingest", "/ingest"),
            java.util.Map.entry("Analyze", "/analyze"),
            java.util.Map.entry("Autodev", "/autodev"),
            java.util.Map.entry("Docs", "/docs"),
            java.util.Map.entry("Tickets", "/tickets"),
            java.util.Map.entry("Pull Requests", "/prs"),
            java.util.Map.entry("Learning", "/learning"),
            java.util.Map.entry("Conventions", "/conventions"),
            java.util.Map.entry("Rule Packs", "/rulepacks"),
            java.util.Map.entry("/rulepacks", "/rulepacks"),
            java.util.Map.entry("Token Usage", "/tokens"),
            java.util.Map.entry("Avengers", "/avengers"),
            java.util.Map.entry("HAWKEYE", "/security"),
            java.util.Map.entry("ORACLE Budget", "/oracle"),
            java.util.Map.entry("Architecture", "/architecture")
    );

    @When("I navigate to the {string} page via sidebar")
    public void navigateViaSidebar(String pageName) {
        String path = PAGE_PATHS.getOrDefault(pageName, "/" + pageName.toLowerCase());
        driver.get(BASE_URL + path);
        waitForPageLoad();
    }

    @When("I click the {string} button")
    public void clickButton(String buttonText) {
        WebElement button = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(.,'" + buttonText + "')]")));
        button.click();
        waitForPageLoad();
    }

    @Then("I see the header with text {string}")
    public void seeHeaderText(String expectedText) {
        WebElement header = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("header")));
        assertThat(header.getText()).contains(expectedText);
    }

    @Then("I see the sidebar with navigation items {string}")
    public void seeSidebarNavItems(String commaSeparated) {
        String[] expected = commaSeparated.split(",\\s*");
        WebElement sidebar = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".MuiDrawer-root, nav, [role='navigation']")));
        String sidebarText = sidebar.getText();
        for (String item : expected) {
            assertThat(sidebarText).contains(item.trim());
        }
    }

    @Then("I see the footer with text {string}")
    public void seeFooterText(String expectedText) {
        WebElement footer = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("footer")));
        assertThat(footer.getText().toUpperCase()).contains(expectedText.toUpperCase());
    }

    @Then("the page title area contains {string}")
    public void pageTitleContains(String expectedTitle) {
        waitForPageLoad();
        WebElement main = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("main")));
        assertThat(main.getText()).contains(expectedTitle);
    }

    @Then("the URL path is {string}")
    public void urlPathIs(String expectedPath) {
        wait.until(ExpectedConditions.urlContains(expectedPath));
        assertThat(driver.getCurrentUrl()).contains(expectedPath);
    }

    @Then("I see a table with columns {string}")
    public void seeTableWithColumns(String commaSeparated) {
        String[] expected = commaSeparated.split(",\\s*");
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("table")));
        List<WebElement> headers = driver.findElements(By.cssSelector("thead th, .MuiTableHead-root th"));
        List<String> headerTexts = headers.stream().map(WebElement::getText).toList();
        for (String col : expected) {
            assertThat(headerTexts).anyMatch(h -> h.contains(col.trim()));
        }
    }

    @Then("I see the empty state message {string}")
    public void seeEmptyStateMessage(String expected) {
        WebElement body = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("main")));
        waitForPageLoad();
        assertThat(body.getText()).contains(expected);
    }

    @Then("I see a button labeled {string}")
    public void seeButton(String buttonText) {
        WebElement button = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//button[contains(.,'" + buttonText + "')]")));
        assertThat(button.isDisplayed()).isTrue();
    }

    @Then("I see input fields {string}")
    public void seeInputFields(String commaSeparated) {
        String[] expected = commaSeparated.split(",\\s*");
        waitForPageLoad();
        for (String label : expected) {
            List<WebElement> fields = driver.findElements(By.xpath(
                    "//label[contains(.,'" + label.trim() + "')] | //input[@placeholder='" + label.trim() + "']"));
            assertThat(fields).as("Field with label '" + label.trim() + "' should exist").isNotEmpty();
        }
    }

    @Then("I see dropdown selects for {string}")
    public void seeDropdownSelects(String commaSeparated) {
        String[] expected = commaSeparated.split(",\\s*");
        waitForPageLoad();
        for (String label : expected) {
            List<WebElement> selects = driver.findElements(By.xpath(
                    "//label[contains(.,'" + label.trim() + "')]"));
            assertThat(selects).as("Select with label '" + label.trim() + "' should exist").isNotEmpty();
        }
    }

    @Then("I see a file upload area with text {string}")
    public void seeFileUploadArea(String expectedText) {
        WebElement main = driver.findElement(By.tagName("main"));
        assertThat(main.getText()).contains(expectedText);
    }

    @Then("I see an error message containing {string}")
    public void seeErrorMessage(String expectedText) {
        WebElement alert = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("[role='alert'], .MuiAlert-root")));
        assertThat(alert.getText().toLowerCase()).contains(expectedText.toLowerCase());
    }

    @Then("I see a project selector dropdown")
    public void seeProjectSelector() {
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//label[contains(.,'Project')]")));
    }

    @Then("I see a requirement text area")
    public void seeRequirementTextArea() {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("textarea")));
    }

    @Then("I see a category filter dropdown")
    public void seeCategoryFilter() {
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//label[contains(.,'Category')]")));
    }

    @And("the {string} sidebar item is highlighted")
    public void sidebarItemHighlighted(String itemName) {
        WebElement selected = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".Mui-selected")));
        assertThat(selected.getText()).contains(itemName);
    }

    @And("the {string} sidebar item is not highlighted")
    public void sidebarItemNotHighlighted(String itemName) {
        List<WebElement> selectedItems = driver.findElements(By.cssSelector(".Mui-selected"));
        for (WebElement el : selectedItems) {
            assertThat(el.getText()).doesNotContain(itemName);
        }
    }

    private void waitForPageLoad() {
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("main")));
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".MuiListItemButton-root")));
    }

    @When("I fill in the Ingest form with project {string} name {string} repo {string}")
    public void fillIngestForm(String projectId, String projectName, String repoUrl) {
        waitForPageLoad();
        WebElement projectInput = driver.findElement(By.xpath(
                "//label[contains(.,'Project ID')]/following::input[1]"));
        projectInput.clear();
        projectInput.sendKeys(projectId);
        WebElement nameInput = driver.findElement(By.xpath(
                "//label[contains(.,'Project Name')]/following::input[1]"));
        nameInput.clear();
        nameInput.sendKeys(projectName);
        WebElement urlInput = driver.findElement(By.xpath(
                "//label[contains(.,'GitHub URL')]/following::input[1]"));
        urlInput.clear();
        urlInput.sendKeys(repoUrl);
    }

    @Then("I do not see an HTTP {int} error toast or alert")
    public void noHttpErrorOnPage(int httpCode) {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String body = driver.findElement(By.tagName("body")).getText();
        String code = String.valueOf(httpCode);
        assertThat(body)
                .as("Page must not surface raw HTTP " + httpCode + " to the user")
                .doesNotContain("HTTP " + code)
                .doesNotContain("\"code\":" + code)
                .doesNotContain("status code " + code);
    }

    @Then("I eventually see a progress message containing {string}")
    public void seeProgressMessage(String expected) {
        wait.until(d -> d.findElement(By.tagName("body")).getText().toLowerCase()
                .contains(expected.toLowerCase()));
    }

    @Then("a snackbar containing {string} appears within {int} seconds")
    public void snackbarAppears(String expectedText, int seconds) {
        WebDriverWait localWait = new WebDriverWait(driver, Duration.ofSeconds(seconds));
        localWait.until(d -> {
            List<WebElement> snackbars = d.findElements(
                    By.cssSelector(".MuiSnackbar-root, [role='alert']"));
            return snackbars.stream().anyMatch(el ->
                    el.getText().toLowerCase().contains(expectedText.toLowerCase()));
        });
    }

    @Then("a progress bar is visible")
    public void progressBarVisible() {
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector(".MuiLinearProgress-root, .MuiCircularProgress-root, [role='progressbar']")));
    }

    @When("I wait {int} seconds")
    public void waitSeconds(int seconds) {
        try { Thread.sleep(seconds * 1000L); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Then("the browser console has no API requests that returned {int}")
    public void consoleHasNoStatus(int httpCode) {
        org.openqa.selenium.logging.LogEntries entries =
                driver.manage().logs().get(org.openqa.selenium.logging.LogType.BROWSER);
        for (org.openqa.selenium.logging.LogEntry e : entries) {
            String msg = e.getMessage();
            if (msg.contains(" " + httpCode + " ") || msg.contains(" " + httpCode + ")")) {
                throw new AssertionError("Browser saw HTTP " + httpCode + ": " + msg);
            }
        }
    }
}
