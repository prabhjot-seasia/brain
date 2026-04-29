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

@DisplayName("SpringXmlContextParser")
class SpringXmlContextParserTest {

    private final SpringXmlContextParser parser = new SpringXmlContextParser();

    @Test
    @DisplayName("extracts RabbitMQ listener queues + counts beans from Spring XML context")
    void extractsRabbitListeners(@TempDir Path projectRoot) throws IOException {
        Path springDir = projectRoot.resolve("src/main/resources/config/spring");
        Files.createDirectories(springDir);
        Files.writeString(springDir.resolve("listenerContext.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:rabbit="http://www.springframework.org/schema/rabbit"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <bean id="orderService" class="com.example.OrderService"/>
                  <rabbit:listener-container connection-factory="cf">
                    <rabbit:listener queue-names="orders.created,orders.cancelled" ref="orderService"/>
                  </rabbit:listener-container>
                </beans>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConsumedQueues()).hasSize(2);
        assertThat(projectNode.getConsumedQueues())
                .allMatch(q -> q.getQueueType().equals("RABBITMQ") && q.getSource().equals("SPRING_XML"));
        assertThat(result.stats().get("beans")).isEqualTo(1);
        assertThat(result.stats().get("queues")).isEqualTo(2);
    }

    @Test
    @DisplayName("does not throw on malformed XML; skips bad file and continues")
    void doesNotThrowOnMalformedXml(@TempDir Path projectRoot) throws IOException {
        Path springDir = projectRoot.resolve("src/main/resources/config/spring");
        Files.createDirectories(springDir);
        Files.writeString(springDir.resolve("brokenContext.xml"),
                "<?xml version=\"1.0\"?><beans><bean id=\"x\" class=\"X\"/></not-closed>");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConsumedQueues()).isEmpty();
        assertThat(result.stats().get("beans")).isEqualTo(0);
    }

    @Test
    @DisplayName("rejects XML with DOCTYPE declaration (XXE protection)")
    void rejectsDoctypeXxe(@TempDir Path projectRoot) throws IOException {
        Path springDir = projectRoot.resolve("src/main/resources/config/spring");
        Files.createDirectories(springDir);
        Files.writeString(springDir.resolve("xxeContext.xml"),
                "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><beans><bean id=\"&x;\"/></beans>");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(result.stats().get("beans")).isEqualTo(0);
    }
}
