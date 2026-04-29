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

@DisplayName("GraphQlSdlParser")
class GraphQlSdlParserTest {

    private final GraphQlSdlParser parser = new GraphQlSdlParser();

    @Test
    @DisplayName("parses GraphQL SDL into GraphQlSchemaNode with type/query/mutation fields")
    void parsesGraphQlSdl(@TempDir Path projectRoot) throws IOException {
        Path graphqlDir = projectRoot.resolve("src/main/resources/graphql");
        Files.createDirectories(graphqlDir);
        Files.writeString(graphqlDir.resolve("schema.graphqls"), """
                type Query {
                  order(id: ID!): Order
                  orders(limit: Int): [Order!]!
                }

                type Mutation {
                  createOrder(input: CreateOrderInput!): Order
                }

                type Order {
                  id: ID!
                  status: String!
                  amount: Float
                }

                input CreateOrderInput {
                  amount: Float!
                  currency: String!
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getGraphQlSchemas()).hasSize(1);
        var schema = projectNode.getGraphQlSchemas().get(0);
        assertThat(schema.getQueryFields()).contains("order", "orders");
        assertThat(schema.getMutationFields()).contains("createOrder");
        assertThat(schema.getTypeNames()).contains("Query", "Mutation", "Order", "CreateOrderInput");
        assertThat(result.stats().get("schemas")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no GraphQL files exist")
    void supportsFalseWithoutFiles(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
