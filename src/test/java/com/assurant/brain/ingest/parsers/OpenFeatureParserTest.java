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

@DisplayName("OpenFeatureParser")
class OpenFeatureParserTest {

    private final OpenFeatureParser parser = new OpenFeatureParser();

    @Test
    @DisplayName("parses flagd JSON into FeatureFlagNode")
    void parsesFlagd(@TempDir Path projectRoot) throws IOException {
        Path openfeature = projectRoot.resolve("src/main/resources/openfeature");
        Files.createDirectories(openfeature);
        Files.writeString(openfeature.resolve("flagd.json"), """
                {
                  "flags": {
                    "enable_new_pricing_engine": {
                      "state": "ENABLED",
                      "variants": { "on": true, "off": false },
                      "defaultVariant": "off"
                    },
                    "promo_max_discount_percent": {
                      "state": "ENABLED",
                      "variants": { "low": 10, "high": 25 },
                      "defaultVariant": "low"
                    }
                  }
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getFeatureFlags()).hasSize(2);
        assertThat(projectNode.getFeatureFlags())
                .anyMatch(f -> f.getName().equals("enable_new_pricing_engine")
                        && f.getFlagType().equals("BOOLEAN")
                        && f.getRolloutStatus().equals("ENABLED"));
        assertThat(projectNode.getFeatureFlags())
                .anyMatch(f -> f.getName().equals("promo_max_discount_percent")
                        && f.getFlagType().equals("NUMBER"));
        assertThat(result.stats().get("featureFlags")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no flag config exists")
    void supportsFalseWithoutFlagFile(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
