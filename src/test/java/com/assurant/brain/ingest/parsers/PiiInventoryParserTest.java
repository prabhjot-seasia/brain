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

@DisplayName("PiiInventoryParser")
class PiiInventoryParserTest {

    private final PiiInventoryParser parser = new PiiInventoryParser();

    @Test
    @DisplayName("parses DSAR-style JSON inventory into PiiTagNode set")
    void parsesPiiInventory(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("pii-inventory.json"), """
                [
                  { "category": "EMAIL", "targetType": "COLUMN", "target": "users.email", "sensitivity": "HIGH" },
                  { "category": "SSN", "targetType": "COLUMN", "target": "customers.ssn", "sensitivity": "CRITICAL" },
                  { "category": "NAME", "targetType": "FIELD", "target": "OrderDto.customerName" }
                ]
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getPiiTags()).hasSize(3);
        assertThat(projectNode.getPiiTags())
                .anyMatch(t -> t.getCategory().equals("SSN") && t.getSensitivity().equals("CRITICAL"));
        assertThat(projectNode.getPiiTags())
                .anyMatch(t -> t.getTargetIdentifier().equals("OrderDto.customerName")
                        && t.getTargetType().equals("FIELD"));
        assertThat(result.stats().get("piiTags")).isEqualTo(3);
    }

    @Test
    @DisplayName("supports() false when no PII inventory exists")
    void supportsFalseWithoutInventory(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
