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

@DisplayName("BpmnBusinessProcessParser")
class BpmnBusinessProcessParserTest {

    private final BpmnBusinessProcessParser parser = new BpmnBusinessProcessParser();

    @Test
    @DisplayName("emits BusinessProcessNode per <bpmn:process> in a .bpmn file")
    void parsesBpmn(@TempDir Path projectRoot) throws IOException {
        Path dir = projectRoot.resolve("bpmn");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("orders.bpmn"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="OrderFulfilment" name="Order Fulfilment" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                  </bpmn:process>
                  <bpmn:process id="ReturnsHandling" name="Returns Handling">
                    <bpmn:startEvent id="rstart"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getBusinessProcesses()).hasSize(2);
        assertThat(projectNode.getBusinessProcesses())
                .anyMatch(p -> p.getName().equals("Order Fulfilment") && p.getProcessType().equals("BPMN"))
                .anyMatch(p -> p.getName().equals("Returns Handling"));
        assertThat(result.stats().get("businessProcesses")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no bpmn directory exists")
    void supportsFalseWithoutDir(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed BPMN XML does not throw — best effort")
    void malformedBpmnGraceful(@TempDir Path projectRoot) throws IOException {
        Path dir = projectRoot.resolve("bpmn");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("broken.bpmn"), "<bpmn:process id=\"X\" unclosed");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);

        assertThat(result.stats().get("businessProcesses")).isEqualTo(0);
    }
}
