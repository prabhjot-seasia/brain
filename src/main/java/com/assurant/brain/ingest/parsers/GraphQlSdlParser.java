package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.GraphQlSchemaNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class GraphQlSdlParser implements ArtifactParser {

    private static final List<String> SCAN_DIRECTORIES = List.of(
            "src/main/resources",
            "src/main/resources/graphql",
            "src/main/resources/schema",
            "graphql",
            "schema");

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^\\s*(?:scalar|type|interface|union|enum|input)\\s+(\\w+)\\b");
    private static final Pattern QUERY_BLOCK = Pattern.compile(
            "(?s)\\btype\\s+Query\\s*\\{(.*?)\\}");
    private static final Pattern MUTATION_BLOCK = Pattern.compile(
            "(?s)\\btype\\s+Mutation\\s*\\{(.*?)\\}");
    private static final Pattern SUBSCRIPTION_BLOCK = Pattern.compile(
            "(?s)\\btype\\s+Subscription\\s*\\{(.*?)\\}");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "(?m)^\\s*(\\w+)\\s*[(:]\\s*");

    private static final int MAX_TYPE_NAMES = 5000;
    private static final int MAX_FIELDS = 1000;

    @Override
    public String name() {
        return "GraphQlSdlParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findSchemaFiles(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, GraphQlSchemaNode> schemasById = new LinkedHashMap<>();

        for (Path file : findSchemaFiles(context.projectPath())) {
            try {
                byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(
                        file, IngestPathFilter.MAX_PARSE_BYTES);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String relativePath = context.projectPath().relativize(file).toString();
                GraphQlSchemaNode node = buildSchema(content, relativePath, context);
                schemasById.put(node.getId(), node);
            } catch (IOException | RuntimeException e) {
                log.debug("GraphQlSdlParser skipped {}: {}", file, e.getMessage());
            }
        }

        attachSchemas(context.projectNode(), schemasById.values());
        log.info("GraphQlSdlParser ingested {} GraphQL schemas for project={}",
                schemasById.size(), context.projectId());
        return ParseResult.of(Map.of("schemas", schemasById.size()));
    }

    private List<Path> findSchemaFiles(Path projectPath) {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (String dir : SCAN_DIRECTORIES) {
            Path resolved = projectPath.resolve(dir);
            if (!Files.isDirectory(resolved)) continue;
            try (Stream<Path> stream = IngestPathFilter.safeWalk(projectPath, resolved, 5)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".graphql") || name.endsWith(".graphqls")
                                    || name.endsWith(".gql");
                        })
                        .forEach(unique::add);
            } catch (IOException e) {
                log.debug("GraphQlSdlParser walk failed for {}: {}", resolved, e.getMessage());
            }
        }
        return new ArrayList<>(unique);
    }

    private GraphQlSchemaNode buildSchema(String content, String relativePath, IngestionContext context) {
        GraphQlSchemaNode node = new GraphQlSchemaNode();
        node.setId(context.projectId() + ":graphql:" + relativePath);
        node.setProjectId(context.projectId());
        node.setSourceFile(relativePath);
        node.setTypeNames(extractTypeNames(content));
        node.setQueryFields(extractFields(content, QUERY_BLOCK));
        node.setMutationFields(extractFields(content, MUTATION_BLOCK));
        node.setSubscriptionFields(extractFields(content, SUBSCRIPTION_BLOCK));
        return node;
    }

    private List<String> extractTypeNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TYPE_DECL.matcher(content);
        while (m.find() && names.size() < MAX_TYPE_NAMES) names.add(m.group(1));
        return new ArrayList<>(names);
    }

    private List<String> extractFields(String content, Pattern blockPattern) {
        Matcher m = blockPattern.matcher(content);
        if (!m.find()) return List.of();
        String block = m.group(1);
        List<String> fields = new ArrayList<>();
        Matcher fieldMatcher = FIELD_DECL.matcher(block);
        while (fieldMatcher.find() && fields.size() < MAX_FIELDS) fields.add(fieldMatcher.group(1));
        return fields;
    }

    private void attachSchemas(ProjectNode projectNode, Iterable<GraphQlSchemaNode> schemas) {
        Set<String> existing = new HashSet<>();
        projectNode.getGraphQlSchemas().forEach(s -> existing.add(s.getId()));
        for (GraphQlSchemaNode schema : schemas) {
            if (existing.add(schema.getId())) {
                projectNode.getGraphQlSchemas().add(schema);
            }
        }
    }
}
