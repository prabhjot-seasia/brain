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

@DisplayName("FinOpsCostTagParser")
class FinOpsCostTagParserTest {

    private final FinOpsCostTagParser parser = new FinOpsCostTagParser();

    @Test
    @DisplayName("parses Cost Explorer ResultsByTime+Groups into CostTagNode")
    void parsesResultsByTime(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("cost-explorer.json"), """
                {
                  "ResultsByTime": [
                    {
                      "TimePeriod": { "Start": "2026-04-01", "End": "2026-05-01" },
                      "Groups": [
                        {
                          "Keys": ["user:Service$payments-api"],
                          "Metrics": { "UnblendedCost": { "Amount": "42850.50", "Unit": "USD" } }
                        },
                        {
                          "Keys": ["user:Service$ledger"],
                          "Metrics": { "UnblendedCost": { "Amount": "12100.00", "Unit": "USD" } }
                        }
                      ]
                    }
                  ]
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getCostTags()).hasSize(2);
        assertThat(projectNode.getCostTags())
                .anyMatch(t -> t.getTagValue().equals("payments-api") && t.getMonthlyCostUsd() == 42850.50);
        assertThat(result.stats().get("costTags")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no cost-explorer JSON exists")
    void supportsFalseWithoutExport(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
