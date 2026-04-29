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

@DisplayName("EcsConfigYamlParser")
class EcsConfigYamlParserTest {

    private final EcsConfigYamlParser parser = new EcsConfigYamlParser();

    @Test
    @DisplayName("parses .infra/config.yaml ecs block into EcsServiceConfigNode")
    void parsesEcsConfig(@TempDir Path projectRoot) throws IOException {
        Path infraDir = projectRoot.resolve(".infra");
        Files.createDirectories(infraDir);
        Files.writeString(infraDir.resolve("config.yaml"), """
                ecs:
                  cpu: 1024
                  memory: 2048
                  desiredCount: 3
                  health_check_path: /actuator/health
                  scaling:
                    target_tracking:
                      cpu_utilization: 60
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getEcsServiceConfig()).isNotNull();
        assertThat(projectNode.getEcsServiceConfig().getCpu()).isEqualTo("1024");
        assertThat(projectNode.getEcsServiceConfig().getMemory()).isEqualTo("2048");
        assertThat(projectNode.getEcsServiceConfig().getDesiredCount()).isEqualTo(3);
        assertThat(projectNode.getEcsServiceConfig().getHealthCheckPath()).isEqualTo("/actuator/health");
        assertThat(result.stats().get("desiredCount")).isEqualTo(3);
    }
}
