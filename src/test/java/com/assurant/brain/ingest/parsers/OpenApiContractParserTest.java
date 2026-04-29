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

@DisplayName("OpenApiContractParser")
class OpenApiContractParserTest {

    private final OpenApiContractParser parser = new OpenApiContractParser();

    @Test
    @DisplayName("parses OpenAPI 3.x spec into ExternalApiContractNode + endpoints")
    void parsesOpenApi(@TempDir Path projectRoot) throws IOException {
        Path apiDir = projectRoot.resolve("src/main/resources/api");
        Files.createDirectories(apiDir);
        Files.writeString(apiDir.resolve("openapi.yaml"), """
                openapi: 3.0.3
                info:
                  title: Promoter
                  version: 1.0.0
                paths:
                  /v1/promotions:
                    get:
                      summary: List promotions
                    post:
                      summary: Create promotion
                  /v1/promotions/{id}:
                    delete:
                      summary: Delete promotion
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getContracts()).hasSize(1);
        assertThat(projectNode.getContracts().get(0).getEndpoints()).hasSize(3);
        assertThat(projectNode.getContracts().get(0).getServiceName()).isEqualTo("promoter");
        assertThat(result.stats().get("specs")).isEqualTo(1);
        assertThat(result.stats().get("endpoints")).isEqualTo(3);
    }

    @Test
    @DisplayName("ignores non-OpenAPI yaml files in candidate directories")
    void ignoresNonOpenApi(@TempDir Path projectRoot) throws IOException {
        Path docs = projectRoot.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("openapi.yaml"), """
                kind: Kustomization
                resources:
                  - deployment.yaml
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getContracts()).isEmpty();
    }
}
