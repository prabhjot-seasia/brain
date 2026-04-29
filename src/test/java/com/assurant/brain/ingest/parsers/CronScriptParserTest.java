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

@DisplayName("CronScriptParser")
class CronScriptParserTest {

    private final CronScriptParser parser = new CronScriptParser();

    @Test
    @DisplayName("emits ScheduledScriptNode for shell + sql scripts with cron hint extraction")
    void parsesCronScripts(@TempDir Path projectRoot) throws IOException {
        Path cronDir = projectRoot.resolve("src/cronjobs");
        Files.createDirectories(cronDir);
        Files.writeString(cronDir.resolve("vzw-feed.sh"), """
                #!/bin/bash
                # cron: 0 2 * * *
                echo loading vzw feed
                """);
        Files.writeString(cronDir.resolve("cleanup-orders.sql"), """
                -- Orders cleanup migration
                DELETE FROM orders WHERE created_at < now() - interval '90 days';
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getScheduledScripts()).hasSize(2);
        assertThat(projectNode.getScheduledScripts())
                .anyMatch(s -> s.getScriptType().equals("SHELL") && s.getCronExpression().equals("0 2 * * *"));
        assertThat(projectNode.getScheduledScripts())
                .anyMatch(s -> s.getScriptType().equals("SQL") && s.getPurpose().equals("CLEANUP"));
        assertThat(result.stats().get("scheduledScripts")).isEqualTo(2);
    }
}
