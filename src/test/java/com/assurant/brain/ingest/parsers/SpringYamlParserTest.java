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

@DisplayName("SpringYamlParser")
class SpringYamlParserTest {

    private final SpringYamlParser parser = new SpringYamlParser();

    @Test
    @DisplayName("extracts apps.<name> URL prefixes as ServiceNodes")
    void extractsAppsUrlPrefix(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                apps:
                  catalog: https://${devops.common-services.host}/hylacatalog
                  promoter: https://promoter.example.com
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getCalledServices()).hasSize(2);
        assertThat(projectNode.getCalledServices()).anyMatch(s -> s.getName().equals("catalog"));
        assertThat(projectNode.getCalledServices()).anyMatch(s -> s.getName().equals("promoter"));
        assertThat(result.stats().get("services")).isEqualTo(2);
    }

    @Test
    @DisplayName("extracts SQS / Kafka / RabbitMQ queue references as QueueNodes")
    void extractsQueueReferences(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                aws:
                  sqs:
                    payment-events:
                      queue-url: https://sqs.us-east-1.amazonaws.com/123456789012/payment-events
                spring:
                  kafka:
                    template:
                      default-topic: orders.created
                  rabbitmq:
                    routing-key: shipments.created
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConsumedQueues())
                .anyMatch(q -> q.getQueueType().equals("SQS") && q.getName().equals("payment-events"));
        assertThat(projectNode.getConsumedQueues())
                .anyMatch(q -> q.getQueueType().equals("KAFKA") && q.getName().equals("orders.created"));
        assertThat(projectNode.getConsumedQueues())
                .anyMatch(q -> q.getQueueType().equals("RABBITMQ") && q.getName().equals("shipments.created"));
        assertThat(result.stats().get("queues")).isEqualTo(3);
    }

    @Test
    @DisplayName("ignores spring infrastructure keys (datasource / jpa / cloud config)")
    void ignoresSpringInfrastructureKeys(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/brain
                  jpa:
                    show-sql: true
                  cloud:
                    config:
                      uri: http://configserver.cloud/
                  neo4j:
                    uri: bolt://localhost:7687
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getCalledServices()).isEmpty();
    }

    @Test
    @DisplayName("supports multi-document YAML with --- profile separators")
    void supportsMultiDocumentYaml(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                apps:
                  catalog: https://catalog.dev
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                apps:
                  catalog: https://catalog.prod
                  payments: https://payments.prod
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getCalledServices()).hasSize(2);
        assertThat(projectNode.getCalledServices()).anyMatch(s -> s.getName().equals("catalog"));
        assertThat(projectNode.getCalledServices()).anyMatch(s -> s.getName().equals("payments"));
    }

    @Test
    @DisplayName("does not throw on malformed YAML; skips bad file and continues")
    void doesNotThrowOnMalformedYaml(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application-broken.yml"),
                "apps:\n  catalog: https://x\n\tinvalid: tab indent\n");
        Files.writeString(resources.resolve("application.yml"),
                "apps:\n  promoter: https://promoter.example.com\n");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);

        assertThat(projectNode.getCalledServices()).anyMatch(s -> s.getName().equals("promoter"));
        assertThat(result.stats().get("yamlFiles")).isEqualTo(2);
    }
}
