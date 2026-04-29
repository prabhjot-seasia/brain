package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AutomationTestParser")
class AutomationTestParserTest {

    private final AutomationTestParser parser = new AutomationTestParser();

    @Test
    @DisplayName("detects Cypress tests by .cy.ts suffix")
    void detectsCypress(@TempDir Path projectRoot) throws IOException {
        Path dir = projectRoot.resolve("cypress");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("login.cy.ts"), "describe('login', () => {})");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getAutomationTests()).hasSize(1);
        var node = projectNode.getAutomationTests().get(0);
        assertThat(node.getFramework()).isEqualTo("CYPRESS");
        assertThat(node.getPath()).contains("login.cy.ts");
        assertThat(result.stats().get("automationTestNodes")).isEqualTo(1);
    }

    @Test
    @DisplayName("detects Playwright .spec.ts and Selenium-marker Java files")
    void detectsPlaywrightAndSelenium(@TempDir Path projectRoot) throws IOException {
        Path e2e = projectRoot.resolve("tests/e2e");
        Files.createDirectories(e2e);
        Files.writeString(e2e.resolve("home.spec.ts"), "test('home', () => {})");

        Path automation = projectRoot.resolve("automation");
        Files.createDirectories(automation);
        Files.writeString(automation.resolve("LoginIT.java"),
                "import org.openqa.selenium.WebDriver;\nclass LoginIT { WebDriver d; }");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        parser.parse(context);

        assertThat(projectNode.getAutomationTests())
                .anyMatch(n -> "PLAYWRIGHT".equals(n.getFramework()))
                .anyMatch(n -> "SELENIUM".equals(n.getFramework()));
    }

    @Test
    @DisplayName("supports() false when no automation directory exists")
    void supportsFalseWithoutDir(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
