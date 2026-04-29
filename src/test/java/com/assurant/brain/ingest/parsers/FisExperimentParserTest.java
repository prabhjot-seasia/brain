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

@DisplayName("FisExperimentParser")
class FisExperimentParserTest {

    private final FisExperimentParser parser = new FisExperimentParser();

    @Test
    @DisplayName("parses AWS FIS experimentTemplate JSON into ChaosExperimentNode")
    void parsesFisTemplate(@TempDir Path projectRoot) throws IOException {
        Path fisDir = projectRoot.resolve(".infra/fis");
        Files.createDirectories(fisDir);
        Files.writeString(fisDir.resolve("kill-payments-pod.json"), """
                {
                  "experimentTemplate": {
                    "arn": "arn:aws:fis:us-east-1:111:experiment-template/EXT123",
                    "description": "kill payments pod",
                    "actions": {
                      "killPod": { "actionId": "aws:eks:terminate-nodegroup-instances" }
                    },
                    "targets": {
                      "pods": { "resourceType": "aws:eks:pod" }
                    }
                  }
                }
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getChaosExperiments()).hasSize(1);
        var experiment = projectNode.getChaosExperiments().get(0);
        assertThat(experiment.getName()).isEqualTo("kill payments pod");
        assertThat(experiment.getTemplateArn()).startsWith("arn:aws:fis");
        assertThat(experiment.getKnownFailureModes()).contains("aws:eks:terminate-nodegroup-instances");
        assertThat(experiment.getTargetServices()).contains("aws:eks:pod");
        assertThat(result.stats().get("experiments")).isEqualTo(1);
    }

    @Test
    @DisplayName("supports() false when no fis directory exists")
    void supportsFalseWithoutFisDir(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed JSON does not throw; experiment skipped")
    void malformedJsonGracefulFailure(@TempDir Path projectRoot) throws IOException {
        Path fisDir = projectRoot.resolve(".infra/fis");
        Files.createDirectories(fisDir);
        Files.writeString(fisDir.resolve("broken.json"), "{ this is not valid");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);
        assertThat(projectNode.getChaosExperiments()).isEmpty();
        assertThat(result.stats().get("experiments")).isEqualTo(0);
    }
}
