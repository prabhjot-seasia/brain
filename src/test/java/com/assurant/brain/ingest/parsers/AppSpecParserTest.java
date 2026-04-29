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

@DisplayName("AppSpecParser")
class AppSpecParserTest {

    private final AppSpecParser parser = new AppSpecParser();

    @Test
    @DisplayName("emits DeployHookNode for each lifecycle phase")
    void parsesAppSpecHooks(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("appspec.yml"), """
                version: 0.0
                os: linux
                hooks:
                  BeforeInstall:
                    - location: scripts/before_install.sh
                      runas: root
                  ApplicationStart:
                    - location: scripts/start.sh
                      runas: ec2-user
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getDeployHooks()).hasSize(2);
        assertThat(projectNode.getDeployHooks())
                .anyMatch(h -> h.getPhase().equals("BeforeInstall")
                        && h.getScriptPath().equals("scripts/before_install.sh"));
        assertThat(result.stats().get("deployHooks")).isEqualTo(2);
    }
}
