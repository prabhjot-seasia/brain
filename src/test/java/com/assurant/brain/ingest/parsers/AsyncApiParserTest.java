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

@DisplayName("AsyncApiParser")
class AsyncApiParserTest {

    private final AsyncApiParser parser = new AsyncApiParser();

    @Test
    @DisplayName("parses AsyncAPI v3 channels into EventSchemaNode set with payload fields")
    void parsesAsyncApi(@TempDir Path projectRoot) throws IOException {
        Path apiDir = projectRoot.resolve("src/main/resources/api");
        Files.createDirectories(apiDir);
        Files.writeString(apiDir.resolve("asyncapi.yaml"), """
                asyncapi: 3.0.0
                info:
                  title: Order Events
                  version: 1.0.0
                channels:
                  orders.created:
                    publish:
                      message:
                        name: OrderCreated
                        contentType: application/json
                        payload:
                          type: object
                          properties:
                            orderId:
                              type: string
                            customerId:
                              type: string
                            totalAmount:
                              type: number
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getEventSchemas()).hasSize(1);
        var schema = projectNode.getEventSchemas().get(0);
        assertThat(schema.getChannelName()).isEqualTo("orders.created");
        assertThat(schema.getMessageName()).isEqualTo("OrderCreated");
        assertThat(schema.getOperation()).isEqualTo("PUBLISH");
        assertThat(schema.getPayloadFields()).containsExactly("orderId", "customerId", "totalAmount");
        assertThat(result.stats().get("eventSchemas")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no AsyncAPI spec exists")
    void supportsFalseWithoutSpec(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
