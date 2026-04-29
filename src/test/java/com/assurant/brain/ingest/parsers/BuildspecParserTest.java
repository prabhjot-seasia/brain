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

@DisplayName("BuildspecParser")
class BuildspecParserTest {

    private final BuildspecParser parser = new BuildspecParser();

    @Test
    @DisplayName("classifies buildspec variants by carrier suffix and version suffix")
    void classifiesVariants(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("buildspec.yml"), "version: 0.2");
        Files.writeString(projectRoot.resolve("buildspec_v2.yml"), "version: 0.2");
        Files.writeString(projectRoot.resolve("buildspec_telus_v2.yml"), "version: 0.2");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getBuildVariants()).hasSize(3);
        assertThat(projectNode.getBuildVariants())
                .anyMatch(v -> v.getName().equals("buildspec.yml") && v.getTarget().equals("DEFAULT"));
        assertThat(projectNode.getBuildVariants())
                .anyMatch(v -> v.getName().equals("buildspec_v2.yml") && v.getTarget().equals("VERSION"));
        assertThat(projectNode.getBuildVariants())
                .anyMatch(v -> v.getName().equals("buildspec_telus_v2.yml")
                        && v.getTarget().equals("CARRIER")
                        && v.getTargetValue().equals("telus"));
        assertThat(result.stats().get("buildVariants")).isEqualTo(3);
    }
}
