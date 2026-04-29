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

@DisplayName("C4StructurizrParser")
class C4StructurizrParserTest {

    private final C4StructurizrParser parser = new C4StructurizrParser();

    @Test
    @DisplayName("parses Structurizr DSL workspace into C4WorkspaceNode")
    void parsesStructurizrDsl(@TempDir Path projectRoot) throws IOException {
        Path docsDir = projectRoot.resolve("docs/architecture");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("workspace.dsl"), """
                workspace "Project Brain" "Multi-repo intelligence" {
                  model {
                    user = person "Developer"
                    brain = softwareSystem "Project Brain" {
                      api = container "Brain API" "Spring Boot"
                      ui  = container "Brain UI" "React"
                      api -> ui "JSON"
                      planner = component "Planner" "RAG + LLM"
                    }
                  }
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getC4Workspace()).isNotNull();
        assertThat(projectNode.getC4Workspace().getWorkspaceName()).isEqualTo("Project Brain");
        assertThat(projectNode.getC4Workspace().getPersons()).contains("Developer");
        assertThat(projectNode.getC4Workspace().getSystems()).contains("Project Brain");
        assertThat(projectNode.getC4Workspace().getContainers()).contains("Brain API", "Brain UI");
        assertThat(projectNode.getC4Workspace().getComponents()).contains("Planner");
        assertThat(result.stats().get("workspaceName")).isEqualTo("Project Brain");
    }

    @Test
    @DisplayName("supports() false when no workspace.dsl exists")
    void supportsFalseWithoutDsl(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
