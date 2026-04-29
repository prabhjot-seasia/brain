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

@DisplayName("LiquibaseSchemaParser")
class LiquibaseSchemaParserTest {

    private final LiquibaseSchemaParser parser = new LiquibaseSchemaParser();

    @Test
    @DisplayName("parses createTable changesets into DatabaseTableNode + columns")
    void parsesCreateTable(@TempDir Path projectRoot) throws IOException {
        Path changelogDir = projectRoot.resolve("src/main/resources/db/changelog");
        Files.createDirectories(changelogDir);
        Files.writeString(changelogDir.resolve("ddl_changelog.yaml"), """
                databaseChangeLog:
                  - changeSet:
                      id: BRAIN-001.01
                      author: brain
                      changes:
                        - createTable:
                            tableName: carrier_config
                            columns:
                              - column:
                                  name: id
                                  type: bigserial
                                  constraints:
                                    nullable: false
                              - column:
                                  name: cou_enabled
                                  type: boolean
                                  defaultValue: false
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getOwnedTables()).hasSize(1);
        assertThat(projectNode.getOwnedTables().get(0).getTableName()).isEqualTo("carrier_config");
        assertThat(projectNode.getOwnedTables().get(0).getPurpose()).isEqualTo("CONFIG");
        assertThat(projectNode.getOwnedTables().get(0).getColumns()).hasSize(2);
        assertThat(result.stats().get("tables")).isEqualTo(1);
    }

    @Test
    @DisplayName("merges addColumn changesets onto existing table")
    void mergesAddColumn(@TempDir Path projectRoot) throws IOException {
        Path changelogDir = projectRoot.resolve("src/main/resources/db/changelog");
        Files.createDirectories(changelogDir);
        Files.writeString(changelogDir.resolve("changelog.yaml"), """
                databaseChangeLog:
                  - changeSet:
                      id: BRAIN-001.01
                      author: brain
                      changes:
                        - createTable:
                            tableName: orders
                            columns:
                              - column:
                                  name: id
                                  type: bigint
                  - changeSet:
                      id: BRAIN-001.02
                      author: brain
                      changes:
                        - addColumn:
                            tableName: orders
                            columns:
                              - column:
                                  name: status
                                  type: varchar(32)
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getOwnedTables()).hasSize(1);
        assertThat(projectNode.getOwnedTables().get(0).getColumns()).hasSize(2);
        assertThat(projectNode.getOwnedTables().get(0).getPurpose()).isEqualTo("DATA");
    }
}
