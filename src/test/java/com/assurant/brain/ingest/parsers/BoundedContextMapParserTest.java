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

@DisplayName("BoundedContextMapParser")
class BoundedContextMapParserTest {

    private final BoundedContextMapParser parser = new BoundedContextMapParser();

    @Test
    @DisplayName("parses YAML context-map.yaml into BoundedContextNode")
    void parsesYamlContextMap(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("context-map.yaml"), """
                name: Payments
                displayName: Payments Domain
                owner: team-payments
                projects:
                  - payments-api
                  - ledger
                ubiquitousLanguage:
                  - Payment
                  - Refund
                  - Settlement
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getBoundedContext()).isNotNull();
        var ctx = projectNode.getBoundedContext();
        assertThat(ctx.getName()).isEqualTo("Payments");
        assertThat(ctx.getOwnerTeamId()).isEqualTo("team-payments");
        assertThat(ctx.getIncludedProjects()).containsExactly("payments-api", "ledger");
        assertThat(ctx.getUbiquitousLanguageTerms()).containsExactly("Payment", "Refund", "Settlement");
        assertThat(ctx.getSource()).isEqualTo("YAML");
        assertThat(result.stats().get("source")).isEqualTo("YAML");
    }

    @Test
    @DisplayName("parses ContextMapper CML BoundedContext + ownedBy")
    void parsesCml(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("context-map.cml"), """
                ContextMap PaymentsContextMap {
                  contains Payments
                }

                BoundedContext Payments implements PaymentManagement {
                  type = FEATURE
                  domainVisionStatement = "Process payments reliably."
                  ownedBy = TeamPayments
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);

        assertThat(projectNode.getBoundedContext()).isNotNull();
        assertThat(projectNode.getBoundedContext().getName()).isEqualTo("Payments");
        assertThat(projectNode.getBoundedContext().getOwnerTeamId()).isEqualTo("TeamPayments");
        assertThat(projectNode.getBoundedContext().getSource()).isEqualTo("CML");
        assertThat(result.stats().get("source")).isEqualTo("CML");
    }

    @Test
    @DisplayName("supports() false when neither context-map.yaml nor .cml exists")
    void supportsFalseWithoutMap(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
