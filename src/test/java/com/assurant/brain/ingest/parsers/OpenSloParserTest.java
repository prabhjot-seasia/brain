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

@DisplayName("OpenSloParser")
class OpenSloParserTest {

    private final OpenSloParser parser = new OpenSloParser();

    @Test
    @DisplayName("parses OpenSLO YAML into SLONode with target percent and window")
    void parsesOpenSloYaml(@TempDir Path projectRoot) throws IOException {
        Path sloDir = projectRoot.resolve("slo");
        Files.createDirectories(sloDir);
        Files.writeString(sloDir.resolve("payments-availability.slo.yaml"), """
                apiVersion: openslo/v1
                kind: SLO
                metadata:
                  name: payments-availability
                spec:
                  service: payments-api
                  description: Payments API availability
                  objectives:
                    - target: 0.9995
                      window: 30d
                  indicator:
                    metadata:
                      name: http-success-ratio
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSlos()).hasSize(1);
        assertThat(projectNode.getSlos().get(0).getServiceName()).isEqualTo("payments-api");
        assertThat(projectNode.getSlos().get(0).getTargetPercent()).isEqualTo(99.95);
        assertThat(projectNode.getSlos().get(0).getWindow()).isEqualTo("30d");
        assertThat(projectNode.getSlos().get(0).getIndicatorType()).isEqualTo("http-success-ratio");
        assertThat(result.stats().get("slos")).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores non-OpenSLO YAML files in slo directory")
    void ignoresNonOpenSloYaml(@TempDir Path projectRoot) throws IOException {
        Path sloDir = projectRoot.resolve("slo");
        Files.createDirectories(sloDir);
        Files.writeString(sloDir.resolve("config.yaml"),
                "apiVersion: kustomize/v1\nkind: Kustomization\n");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getSlos()).isEmpty();
    }
}
