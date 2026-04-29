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

@DisplayName("LogConfigParser")
class LogConfigParserTest {

    private final LogConfigParser parser = new LogConfigParser();

    @Test
    @DisplayName("extracts pattern + root + logger overrides into ConventionNode entries")
    void parsesLog4j2(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("log4j2.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <Configuration>
                  <Appenders>
                    <Console name="STDOUT">
                      <PatternLayout pattern="[%d{ISO8601}] %-5p [%t] %c - %m%n"/>
                    </Console>
                  </Appenders>
                  <Loggers>
                    <Logger name="com.assurant.brain" level="DEBUG"/>
                    <Logger name="org.springframework.ai" level="INFO"/>
                    <Root level="WARN"/>
                  </Loggers>
                </Configuration>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getCategory().equals("LOGGING") && c.getRule().contains("Log pattern:"));
        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getCategory().equals("LOGGING") && c.getRule().contains("Root logger level: WARN"));
        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getRule().contains("com.assurant.brain") && c.getRule().contains("DEBUG"));
        assertThat(result.stats().get("loggingConventions")).isNotNull();
    }

    @Test
    @DisplayName("supports() false when no log config exists")
    void supportsFalseWithoutConfig(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed XML does not throw; pattern extractor degrades safely")
    void malformedXmlGracefulFailure(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("log4j2.xml"),
                "<?xml version=\"1.0\"?><Configuration><Appenders><Console name=\"x\" pattern=\"unclosed");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(result.stats().get("loggingConventions")).isNotNull();
    }

    @Test
    @DisplayName("parses logback.xml variant with <pattern> + <root level=...>")
    void parsesLogback(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("logback.xml"), """
                <configuration>
                  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder>
                      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n</pattern>
                    </encoder>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STDOUT"/>
                  </root>
                </configuration>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getRule().contains("Log pattern:") && c.getRule().contains("HH:mm:ss"));
        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getRule().equals("Root logger level: INFO"));
    }
}
