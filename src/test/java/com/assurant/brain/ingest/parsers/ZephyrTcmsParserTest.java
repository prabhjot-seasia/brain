package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ZephyrTcmsParser")
class ZephyrTcmsParserTest {

    private final ZephyrTcmsParser parser = new ZephyrTcmsParser(new ObjectMapper());

    @Test
    @DisplayName("emits a TestCaseNode per Zephyr export entry")
    void parsesZephyrExport(@TempDir Path projectRoot) throws IOException {
        Path tcms = projectRoot.resolve("tcms");
        Files.createDirectories(tcms);
        Files.writeString(tcms.resolve("zephyr-export.json"), """
                {
                  "testCases": [
                    {"key": "PROJ-T100", "title": "Login happy path", "priority": "HIGH", "status": "ACTIVE",
                     "gherkin": "Given valid creds\\nWhen login\\nThen home page"},
                    {"key": "PROJ-T101", "title": "Login invalid password", "priority": "MEDIUM"}
                  ]
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTestCases()).hasSize(2);
        assertThat(projectNode.getTestCases())
                .anyMatch(t -> t.getExternalId().equals("PROJ-T100")
                        && t.getSource().equals("ZEPHYR")
                        && t.getTitle().contains("Login happy path"));
        assertThat(result.stats().get("tcmsTestCases")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no TCMS export file exists")
    void supportsFalseWithoutExport(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed JSON does not throw — file skipped")
    void malformedJsonGraceful(@TempDir Path projectRoot) throws IOException {
        Path tcms = projectRoot.resolve("tcms");
        Files.createDirectories(tcms);
        Files.writeString(tcms.resolve("xray-export.json"), "{not valid json");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTestCases()).isEmpty();
        assertThat(result.stats().get("tcmsTestCases")).isEqualTo(0);
    }
}
