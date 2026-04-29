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

@DisplayName("CloudWatchLogsInsightsParser")
class CloudWatchLogsInsightsParserTest {

    private final CloudWatchLogsInsightsParser parser = new CloudWatchLogsInsightsParser();

    @Test
    @DisplayName("parses Logs Insights JSON results into LogPatternNode set")
    void parsesJsonResults(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("logs-insights.json"), """
                {
                  "results": [
                    { "@logLevel": "ERROR",
                      "@message": "PaymentService.NullPointerException at OrderService.process",
                      "count": 142 },
                    { "@logLevel": "WARN",
                      "@message": "Retry attempt 3 for downstream call",
                      "count": 67 }
                  ]
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getLogPatterns()).hasSize(2);
        assertThat(projectNode.getLogPatterns())
                .anyMatch(p -> p.getLevel().equals("ERROR") && p.getOccurrences() == 142
                        && p.getTemplate().contains("NullPointerException"));
        assertThat(projectNode.getLogPatterns())
                .anyMatch(p -> p.getLevel().equals("WARN") && p.getOccurrences() == 67);
        assertThat(result.stats().get("logPatterns")).isEqualTo(2);
    }

    @Test
    @DisplayName("parses CSV variant with @message + level + count columns")
    void parsesCsvResults(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("log-patterns.csv"), """
                @message,level,count
                Authentication failed for user,ERROR,12
                Cache miss for key promo:vzw,INFO,890
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getLogPatterns()).hasSize(2);
        assertThat(result.stats().get("logPatterns")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no logs export exists")
    void supportsFalseWithoutExport(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
