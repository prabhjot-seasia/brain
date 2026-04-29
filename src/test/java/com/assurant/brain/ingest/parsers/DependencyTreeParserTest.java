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

@DisplayName("DependencyTreeParser")
class DependencyTreeParserTest {

    private final DependencyTreeParser parser = new DependencyTreeParser();

    @Test
    @DisplayName("parses Maven/Gradle GAV lines into resolved LibraryNode entries")
    void parsesGavTree(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("dependency-tree.txt"), """
                +--- org.springframework.boot:spring-boot-starter-web:3.4.4
                |    +--- org.springframework:spring-core:6.1.0 -> 6.1.5
                |    \\--- com.fasterxml.jackson.core:jackson-databind:2.17.0
                +--- org.projectlombok:lombok:1.18.30
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getLibraries()).hasSize(4);
        assertThat(projectNode.getLibraries())
                .anyMatch(l -> l.getArtifactId().equals("spring-core")
                        && l.getVersion().equals("6.1.5")
                        && l.getPurpose().equals("RESOLVED:6.1.0->6.1.5"));
        assertThat(projectNode.getLibraries())
                .anyMatch(l -> l.getArtifactId().equals("lombok")
                        && l.getVersion().equals("1.18.30")
                        && l.getPurpose().equals("RESOLVED"));
        assertThat(result.stats().get("resolvedLibraries")).isEqualTo(4);
    }

    @Test
    @DisplayName("supports() false when no dependency tree export exists")
    void supportsFalseWithoutExport(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
