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

@DisplayName("PactParser")
class PactParserTest {

    private final PactParser parser = new PactParser();

    @Test
    @DisplayName("emits ExternalApiContractNode with format=PACT and per-interaction endpoints")
    void parsesPactFile(@TempDir Path projectRoot) throws IOException {
        Path pacts = projectRoot.resolve("pacts");
        Files.createDirectories(pacts);
        Files.writeString(pacts.resolve("ce-app-promoter.json"), """
                {
                  "consumer": { "name": "ce-app" },
                  "provider": { "name": "promoter" },
                  "interactions": [
                    {
                      "description": "list promotions",
                      "request": { "method": "GET", "path": "/v1/promotions" },
                      "response": { "status": 200 }
                    },
                    {
                      "description": "create promotion",
                      "request": { "method": "POST", "path": "/v1/promotions" },
                      "response": { "status": 201 }
                    }
                  ]
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getContracts()).hasSize(1);
        var contract = projectNode.getContracts().get(0);
        assertThat(contract.getFormat()).isEqualTo("PACT");
        assertThat(contract.getServiceName()).isEqualTo("promoter");
        assertThat(contract.getEndpoints()).hasSize(2);
        assertThat(contract.getEndpoints())
                .anyMatch(e -> e.getHttpMethod().equals("GET") && e.getPath().equals("/v1/promotions"));
        assertThat(result.stats().get("pactContracts")).isEqualTo(1);
        assertThat(result.stats().get("endpoints")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no pacts directory exists")
    void supportsFalseWithoutPacts(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
