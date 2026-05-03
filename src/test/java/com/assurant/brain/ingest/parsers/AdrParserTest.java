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

@DisplayName("AdrParser")
class AdrParserTest {

    private final AdrParser parser = new AdrParser(new IngestDocumentFactory(testProperties()));

    @Test
    @DisplayName("parses MADR markdown with frontmatter and creates DecisionNode + chunk")
    void parsesMadrFile(@TempDir Path projectRoot) throws IOException {
        Path adrDir = projectRoot.resolve("docs/adr");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("0001-use-postgres.md"), """
                ---
                status: ACCEPTED
                date: 2026-04-01
                ---

                # Use PostgreSQL for primary persistence

                We chose PostgreSQL because of pgvector support.
                """);

        ProjectNode projectNode = new ProjectNode();
        List<Document> docs = new ArrayList<>();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "https://github.com/x/y", projectNode, RepoKind.APPLICATION, docs);

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getDecisions()).hasSize(1);
        assertThat(projectNode.getDecisions().get(0).getStatus()).isEqualTo("ACCEPTED");
        assertThat(projectNode.getDecisions().get(0).getDate()).isEqualTo("2026-04-01");
        assertThat(projectNode.getDecisions().get(0).getTitle()).isEqualTo("Use PostgreSQL for primary persistence");
        assertThat(docs).hasSize(1);
        assertThat(result.stats().get("decisions")).isEqualTo(1);
    }

    @Test
    @DisplayName("returns false from supports() when no ADR directory exists")
    void supportsFalseWithoutAdrDirectory(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("falls back to filename-derived title when no frontmatter or heading present")
    void titleFallsBackToFilename(@TempDir Path projectRoot) throws IOException {
        Path adrDir = projectRoot.resolve("decisions");
        Files.createDirectories(adrDir);
        Files.writeString(adrDir.resolve("0042-deprecate-rest-v1.md"), "Body text without heading.");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-2", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getDecisions()).hasSize(1);
        assertThat(projectNode.getDecisions().get(0).getTitle()).isEqualTo("0042-deprecate-rest-v1");
        assertThat(projectNode.getDecisions().get(0).getStatus()).isEqualTo("PROPOSED");
    }

    private static BrainProperties testProperties() {
        BrainProperties.Rag rag = new BrainProperties.Rag(10, 5, 6000, 1.5, 1.0, 10, 5);
        return new BrainProperties(null, null, rag, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
