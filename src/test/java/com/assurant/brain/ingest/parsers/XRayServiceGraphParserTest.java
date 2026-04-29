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

@DisplayName("XRayServiceGraphParser")
class XRayServiceGraphParserTest {

    private final XRayServiceGraphParser parser = new XRayServiceGraphParser();

    @Test
    @DisplayName("parses X-Ray GetServiceGraph response into RuntimeServiceEdgeNode set")
    void parsesXRayServiceGraph(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("xray-service-graph.json"), """
                {
                  "Services": [
                    {
                      "Name": "payments-api",
                      "Edges": [
                        {
                          "ReferenceName": "ledger",
                          "SummaryStatistics": {
                            "TotalCount": 15800,
                            "AverageResponseTime": 38.7,
                            "ErrorStatistics": { "TotalCount": 12 },
                            "FaultStatistics": { "TotalCount": 4 }
                          }
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

        assertThat(projectNode.getRuntimeServiceEdges()).hasSize(1);
        var edge = projectNode.getRuntimeServiceEdges().get(0);
        assertThat(edge.getFromServiceName()).isEqualTo("payments-api");
        assertThat(edge.getToServiceName()).isEqualTo("ledger");
        assertThat(edge.getFrequency()).isEqualTo(15800);
        assertThat(edge.getP50LatencyMs()).isEqualTo(38.7);
        assertThat(edge.getErrorRate()).isCloseTo(16.0 / 15800.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(edge.getSource()).isEqualTo("AWS_XRAY");
        assertThat(result.stats().get("runtimeEdges")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no xray export is present")
    void supportsFalseWithoutExport(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
