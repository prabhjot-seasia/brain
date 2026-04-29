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

@DisplayName("SettingsPropertiesEndpointMapParser")
class SettingsPropertiesEndpointMapParserTest {

    private final SettingsPropertiesEndpointMapParser parser = new SettingsPropertiesEndpointMapParser();

    @Test
    @DisplayName("emits ApiEndpointMapNode for path-like values, ignores non-paths")
    void parsesEndpointMap(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("settings.properties"), """
                userApi=api/rest/v1/user
                calculateAmount=api/rest/v1/models/{modelCode}/recalculate
                promoter.promotions=/v1/promotions/searches
                ui.title=Hello
                logo.url=https://cdn.example.com/logo.png
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getApiEndpointMap()).hasSize(3);
        assertThat(projectNode.getApiEndpointMap()).anyMatch(n -> n.getAlias().equals("userApi"));
        assertThat(projectNode.getApiEndpointMap()).anyMatch(n -> n.getAlias().equals("promoter.promotions")
                && n.getVersionRange().equals("v1"));
        assertThat(result.stats().get("aliases")).isEqualTo(3);
    }
}
