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

@DisplayName("RunbookParser")
class RunbookParserTest {

    private final RunbookParser parser = new RunbookParser(new IngestDocumentFactory(testProperties()));

    @Test
    @DisplayName("parses runbook markdown with frontmatter into RunbookNode")
    void parsesRunbook(@TempDir Path projectRoot) throws IOException {
        Path runbooks = projectRoot.resolve("runbooks");
        Files.createDirectories(runbooks);
        Files.writeString(runbooks.resolve("payment-failure.md"), """
                ---
                triggers: [payment-error-rate-high, sqs-dlq-non-empty]
                services: [payments-api, ledger]
                ---

                # Payment failure response

                Steps to triage payment failures.
                """);

        ProjectNode projectNode = new ProjectNode();
        List<Document> docs = new ArrayList<>();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, docs);

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getRunbooks()).hasSize(1);
        var runbook = projectNode.getRunbooks().get(0);
        assertThat(runbook.getTitle()).isEqualTo("Payment failure response");
        assertThat(runbook.getTriggers()).containsExactly("payment-error-rate-high", "sqs-dlq-non-empty");
        assertThat(runbook.getTargetServices()).containsExactly("payments-api", "ledger");
        assertThat(docs).hasSize(1);
        assertThat(result.stats().get("runbooks")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no runbook directory exists")
    void supportsFalseWithoutDirectory(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    private static BrainProperties testProperties() {
        BrainProperties.Rag rag = new BrainProperties.Rag(10, 5, 6000, 1.5, 1.0, 10, 5);
        return new BrainProperties(null, null, rag, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
