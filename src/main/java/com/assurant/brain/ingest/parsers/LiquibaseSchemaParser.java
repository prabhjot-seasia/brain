package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.DatabaseColumnNode;
import com.assurant.brain.graph.node.DatabaseTableNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class LiquibaseSchemaParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final List<String> CHANGELOG_DIRECTORIES = List.of(
            "src/main/resources/db/changelog",
            "src/main/liquibase",
            "db/changelog",
            "liquibase");

    private static final Pattern CONFIG_TABLE_PATTERN =
            Pattern.compile("(?i).*(config|setting|flag|feature|carrier|tenant)(_|s_)?$");

    @Override
    public String name() {
        return "LiquibaseSchemaParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return CHANGELOG_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, DatabaseTableNode> tablesByName = new LinkedHashMap<>();

        for (String dir : CHANGELOG_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walkChangelogTree(root, context, tablesByName);
        }

        attachTables(context.projectNode(), tablesByName.values());
        log.info("LiquibaseSchemaParser parsed {} tables for project={}",
                tablesByName.size(), context.projectId());
        return ParseResult.of(Map.of(
                "tables", tablesByName.size(),
                "columns", tablesByName.values().stream().mapToInt(t -> t.getColumns().size()).sum()));
    }

    private void walkChangelogTree(Path root, IngestionContext context,
                                    Map<String, DatabaseTableNode> tables) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .toList();
            for (Path file : files) {
                processFile(file, context, tables);
            }
        } catch (IOException e) {
            log.warn("LiquibaseSchemaParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, IngestionContext context, Map<String, DatabaseTableNode> tables) {
        try (InputStream in = Files.newInputStream(file);
             MappingIterator<Object> docs = YAML_MAPPER.readerFor(Object.class).readValues(in)) {
            while (docs.hasNext()) {
                Object doc = docs.next();
                if (!(doc instanceof Map<?, ?> map)) continue;
                JsonNode root = YAML_MAPPER.convertValue(map, new TypeReference<>() {});
                JsonNode changelog = root.path("databaseChangeLog");
                if (changelog.isArray()) {
                    processChangelog(changelog, context, tables);
                }
            }
        } catch (IOException e) {
            log.debug("LiquibaseSchemaParser skipped {}: {}", file, e.getMessage());
        }
    }

    private void processChangelog(JsonNode changelog, IngestionContext context,
                                   Map<String, DatabaseTableNode> tables) {
        for (JsonNode element : changelog) {
            JsonNode changeSet = element.path("changeSet");
            if (changeSet.isMissingNode()) continue;
            JsonNode changes = changeSet.path("changes");
            if (!changes.isArray()) continue;
            for (JsonNode change : changes) {
                handleChange(change, context, tables);
            }
        }
    }

    private void handleChange(JsonNode change, IngestionContext context,
                              Map<String, DatabaseTableNode> tables) {
        if (change.has("createTable")) {
            handleCreateTable(change.path("createTable"), context, tables);
        } else if (change.has("addColumn")) {
            handleAddColumn(change.path("addColumn"), context, tables);
        }
    }

    private void handleCreateTable(JsonNode createTable, IngestionContext context,
                                    Map<String, DatabaseTableNode> tables) {
        String tableName = createTable.path("tableName").asText("");
        if (StringUtils.isBlank(tableName)) return;
        String schema = createTable.path("schemaName").asText("public");
        DatabaseTableNode table = upsertTable(tableName, schema, context, tables);

        JsonNode columns = createTable.path("columns");
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                JsonNode col = column.path("column");
                if (col.isMissingNode()) col = column;
                appendColumn(table, col);
            }
        }
    }

    private void handleAddColumn(JsonNode addColumn, IngestionContext context,
                                  Map<String, DatabaseTableNode> tables) {
        String tableName = addColumn.path("tableName").asText("");
        if (StringUtils.isBlank(tableName)) return;
        String schema = addColumn.path("schemaName").asText("public");
        DatabaseTableNode table = upsertTable(tableName, schema, context, tables);

        JsonNode columns = addColumn.path("columns");
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                JsonNode col = column.path("column");
                if (col.isMissingNode()) col = column;
                appendColumn(table, col);
            }
        }
    }

    private DatabaseTableNode upsertTable(String tableName, String schema, IngestionContext context,
                                          Map<String, DatabaseTableNode> tables) {
        String id = context.projectId() + ":table:" + schema + "." + tableName;
        return tables.computeIfAbsent(id, k -> {
            DatabaseTableNode table = new DatabaseTableNode();
            table.setId(id);
            table.setProjectId(context.projectId());
            table.setSchemaName(schema);
            table.setTableName(tableName);
            table.setPurpose(CONFIG_TABLE_PATTERN.matcher(tableName).matches() ? "CONFIG" : "DATA");
            table.setSource("LIQUIBASE");
            return table;
        });
    }

    private void appendColumn(DatabaseTableNode table, JsonNode col) {
        String name = col.path("name").asText("");
        if (StringUtils.isBlank(name)) return;
        if (table.getColumns().stream().anyMatch(c -> name.equalsIgnoreCase(c.getColumnName()))) return;

        DatabaseColumnNode column = new DatabaseColumnNode();
        column.setId(table.getId() + "." + name);
        column.setTableId(table.getId());
        column.setColumnName(name);
        column.setDataType(col.path("type").asText(""));
        column.setDefaultValue(col.path("defaultValue").asText(""));
        column.setNullable(col.path("constraints").path("nullable").asBoolean(true));
        column.setComment(col.path("remarks").asText(""));
        table.getColumns().add(column);
    }

    private void attachTables(ProjectNode projectNode, Iterable<DatabaseTableNode> tables) {
        Set<String> existing = new HashSet<>();
        projectNode.getOwnedTables().forEach(t -> existing.add(t.getId()));
        for (DatabaseTableNode table : tables) {
            if (existing.add(table.getId())) {
                projectNode.getOwnedTables().add(table);
            }
        }
    }
}
