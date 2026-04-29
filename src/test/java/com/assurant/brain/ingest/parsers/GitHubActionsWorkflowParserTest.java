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

@DisplayName("GitHubActionsWorkflowParser")
class GitHubActionsWorkflowParserTest {

    private final GitHubActionsWorkflowParser parser = new GitHubActionsWorkflowParser();

    @Test
    @DisplayName("parses workflow with triggers, matrix dimensions, and reusable workflow uses")
    void parsesWorkflow(@TempDir Path projectRoot) throws IOException {
        Path workflowsDir = projectRoot.resolve(".github/workflows");
        Files.createDirectories(workflowsDir);
        Files.writeString(workflowsDir.resolve("publish.yml"), """
                name: Publish multi-client
                on:
                  workflow_dispatch:
                    inputs:
                      client:
                        description: client
                jobs:
                  publish:
                    strategy:
                      matrix:
                        client: [tmobile, totalwireless, xfinitymobile]
                    runs-on: ubuntu-latest
                    steps:
                      - run: echo hi
                  release:
                    uses: assurant/shared-actions/.github/workflows/release.yml@v1
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getCiWorkflows()).hasSize(1);
        assertThat(projectNode.getCiWorkflows().get(0).getTriggers()).contains("workflow_dispatch");
        assertThat(projectNode.getCiWorkflows().get(0).getMatrixDimensions()).contains("client");
        assertThat(projectNode.getCiWorkflows().get(0).getUsesReusableWorkflows()).isNotEmpty();
        assertThat(result.stats().get("workflows")).isEqualTo(1);
    }
}
