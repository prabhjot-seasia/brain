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

@DisplayName("CodeownersParser")
class CodeownersParserTest {

    private final CodeownersParser parser = new CodeownersParser();

    @Test
    @DisplayName("parses CODEOWNERS into TeamNodes with owned paths")
    void parsesCodeownersFile(@TempDir Path projectRoot) throws IOException {
        Path githubDir = projectRoot.resolve(".github");
        Files.createDirectories(githubDir);
        Files.writeString(githubDir.resolve("CODEOWNERS"), """
                # Default
                *           @platform-team
                /src/api/   @api-team @platform-team
                /docs/      @docs-team
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getOwners()).hasSize(3);
        assertThat(projectNode.getOwners()).anyMatch(t -> t.getName().equals("@platform-team")
                && t.getOwnedPaths().contains("*") && t.getOwnedPaths().contains("/src/api/"));
        assertThat(projectNode.getOwners()).anyMatch(t -> t.getName().equals("@api-team"));
        assertThat(projectNode.getOwners()).anyMatch(t -> t.getName().equals("@docs-team"));
        assertThat(result.stats().get("rules")).isEqualTo(3);
    }

    @Test
    @DisplayName("supports() false when no CODEOWNERS file exists")
    void supportsFalseWithoutFile(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("ignores comments and blank lines")
    void ignoresCommentsAndBlanks(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("CODEOWNERS"), """
                # comment line

                /api/  @backend-team
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);
        assertThat(result.stats().get("rules")).isEqualTo(1);
        assertThat(projectNode.getOwners()).hasSize(1);
    }
}
