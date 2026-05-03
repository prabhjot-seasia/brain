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

@DisplayName("PostmortemParser")
class PostmortemParserTest {

    private final PostmortemParser parser = new PostmortemParser(new IngestDocumentFactory(testProperties()));

    @Test
    @DisplayName("parses incident markdown into IncidentNode with severity, root cause, and class hints")
    void parsesIncident(@TempDir Path projectRoot) throws IOException {
        Path postmortems = projectRoot.resolve("docs/postmortems");
        Files.createDirectories(postmortems);
        Files.writeString(postmortems.resolve("2026-01-15-payment-outage.md"), """
                ---
                severity: SEV2
                date: 2026-01-15
                services: [payments-api, ledger]
                ---

                # Payment processing outage

                ## Root Cause

                A null check was missed in OrderService.process(), causing a NullPointerException
                when PaymentRepository returned an empty Optional. The PaymentService catch
                block swallowed the exception silently.

                ## Mitigation

                Added explicit null guard.
                """);

        ProjectNode projectNode = new ProjectNode();
        List<Document> docs = new ArrayList<>();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, docs);

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getIncidents()).hasSize(1);
        var incident = projectNode.getIncidents().get(0);
        assertThat(incident.getSeverity()).isEqualTo("SEV2");
        assertThat(incident.getOccurredAt()).isEqualTo("2026-01-15");
        assertThat(incident.getTitle()).isEqualTo("Payment processing outage");
        assertThat(incident.getRootCauseSummary()).contains("OrderService.process()");
        assertThat(incident.getAffectedClassHints())
                .contains("OrderService", "PaymentRepository", "PaymentService");
        assertThat(incident.getAffectedServices()).containsExactly("payments-api", "ledger");
        assertThat(docs).hasSize(1);
        assertThat(result.stats().get("incidents")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no postmortems directory exists")
    void supportsFalseWithoutDirectory(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    private static BrainProperties testProperties() {
        BrainProperties.Rag rag = new BrainProperties.Rag(10, 5, 6000, 1.5, 1.0, 10, 5);
        return new BrainProperties(null, null, rag, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
