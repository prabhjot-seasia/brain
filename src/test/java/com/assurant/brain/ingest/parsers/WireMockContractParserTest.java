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

@DisplayName("WireMockContractParser")
class WireMockContractParserTest {

    private final WireMockContractParser parser = new WireMockContractParser();

    @Test
    @DisplayName("parses WireMock JSON stubs grouped by service folder into ExternalApiContractNode")
    void parsesWireMockStubs(@TempDir Path projectRoot) throws IOException {
        Path promoter = projectRoot.resolve("src/test/resources/wiremock/promoter");
        Files.createDirectories(promoter);
        Files.writeString(promoter.resolve("promotions.json"), """
                {
                  "request": { "method": "POST", "urlPath": "/v1/promotions/searches" },
                  "response": { "status": 200 }
                }
                """);
        Path haas = projectRoot.resolve("src/test/resources/wiremock/haas");
        Files.createDirectories(haas);
        Files.writeString(haas.resolve("status.json"), """
                {
                  "request": { "method": "GET", "url": "/api/health" },
                  "response": { "status": 200 }
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getContracts()).hasSize(2);
        assertThat(projectNode.getContracts())
                .anyMatch(c -> c.getServiceName().equals("promoter") && c.getEndpoints().size() == 1);
        assertThat(projectNode.getContracts())
                .anyMatch(c -> c.getServiceName().equals("haas") && c.getEndpoints().size() == 1);
        assertThat(result.stats().get("contracts")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() returns false when no wiremock directory exists")
    void supportsFalseWithoutWireMockDir(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("does not throw on malformed JSON stub; continues with other files")
    void doesNotThrowOnMalformedStub(@TempDir Path projectRoot) throws IOException {
        Path bad = projectRoot.resolve("src/test/resources/wiremock/broken");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("malformed.json"), "{ broken");
        Files.writeString(bad.resolve("ok.json"), """
                { "request": { "method": "GET", "url": "/healthz" }, "response": { "status": 200 } }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getContracts()).hasSize(1);
        assertThat(projectNode.getContracts().get(0).getEndpoints()).hasSize(1);
        assertThat(result.stats().get("endpoints")).isEqualTo(1);
    }
}
