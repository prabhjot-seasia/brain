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

@DisplayName("OpenApiAggregateParser")
class OpenApiAggregateParserTest {

    private final OpenApiAggregateParser parser = new OpenApiAggregateParser();

    @Test
    @DisplayName("registers ApiDocumentRegistryNode with master spec + domain folders")
    void registersApiDocumentAggregator(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("main-spec.yaml"), """
                openapi: 3.0.3
                info: { title: Aggregate, version: 1.0 }
                paths: {}
                """);

        Path defs = resources.resolve("definitions");
        Files.createDirectories(defs.resolve("CE"));
        Files.createDirectories(defs.resolve("Gateway"));
        Files.createDirectories(defs.resolve("Shipment"));

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "ce-api-document", projectRoot, null, projectNode, RepoKind.REGISTRY, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getApiDocumentRegistry()).isNotNull();
        assertThat(projectNode.getApiDocumentRegistry().getMasterSpecPath())
                .isEqualTo("src/main/resources/main-spec.yaml");
        assertThat(projectNode.getApiDocumentRegistry().getDomains())
                .containsExactlyInAnyOrder("CE", "Gateway", "Shipment");
        assertThat(result.stats().get("domains")).isEqualTo(3);
    }
}
