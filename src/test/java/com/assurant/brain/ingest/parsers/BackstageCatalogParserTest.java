package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestDocumentFactory;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BackstageCatalogParser")
class BackstageCatalogParserTest {

    private final BackstageCatalogParser parser = new BackstageCatalogParser(new IngestDocumentFactory(testProperties()));

    @Test
    @DisplayName("parses catalog-info.yaml into TeamNode owner + chunk with description")
    void parsesCatalogInfo(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("catalog-info.yaml"), """
                apiVersion: backstage.io/v1alpha1
                kind: Component
                metadata:
                  name: ce-app
                  description: Core CE application service
                spec:
                  type: service
                  lifecycle: production
                  owner: team-payments
                  system: ce-platform
                """);

        ProjectNode projectNode = new ProjectNode();
        List<Document> docs = new ArrayList<>();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, docs);

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getOwners()).hasSize(1);
        assertThat(projectNode.getOwners().get(0).getName()).isEqualTo("@team-payments");
        assertThat(projectNode.getOwners().get(0).getSource()).isEqualTo("BACKSTAGE");
        assertThat(docs).hasSize(1);
        assertThat(result.stats().get("type")).isEqualTo("service");
        assertThat(result.stats().get("lifecycle")).isEqualTo("production");
    }

    @Test
    @DisplayName("does not duplicate owner if CODEOWNERS already added the same handle")
    void deduplicatesOwnerWithCodeowners(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("catalog-info.yaml"), """
                apiVersion: backstage.io/v1alpha1
                kind: Component
                metadata:
                  name: ce-app
                spec:
                  owner: team-payments
                """);

        ProjectNode projectNode = new ProjectNode();
        com.assurant.brain.graph.node.TeamNode existing = new com.assurant.brain.graph.node.TeamNode();
        existing.setId("proj-1:@team-payments");
        existing.setName("@team-payments");
        existing.setSource("CODEOWNERS");
        projectNode.getOwners().add(existing);

        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getOwners()).hasSize(1);
        assertThat(projectNode.getOwners().get(0).getSource()).isEqualTo("CODEOWNERS");
    }

    private static BrainProperties testProperties() {
        BrainProperties.Rag rag = new BrainProperties.Rag(10, 5, 6000, 1.5, 1.0, 10, 5);
        return new BrainProperties(null, null, rag, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
