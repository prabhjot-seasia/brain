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

@DisplayName("PgStatStatementsParser")
class PgStatStatementsParserTest {

    private final PgStatStatementsParser parser = new PgStatStatementsParser();

    @Test
    @DisplayName("parses pg_stat_statements CSV export into SlowQueryNode rows")
    void parsesCsvExport(@TempDir Path projectRoot) throws IOException {
        Path opsDir = projectRoot.resolve("ops");
        Files.createDirectories(opsDir);
        Files.writeString(opsDir.resolve("pg_stat_statements.csv"), """
                query,calls,mean_exec_time,total_exec_time,stddev_exec_time
                SELECT * FROM orders WHERE id = $1,1234,18.5,22810,4.2
                "INSERT INTO order_events(order_id, event) VALUES($1,$2)",890,3.1,2759,1.8
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSlowQueries()).hasSize(2);
        assertThat(projectNode.getSlowQueries())
                .anyMatch(q -> q.getCalls() == 1234 && q.getMeanTimeMs() == 18.5);
        assertThat(result.stats().get("slowQueries")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no pg_stat_statements export is present")
    void supportsFalseWithoutCsv(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
