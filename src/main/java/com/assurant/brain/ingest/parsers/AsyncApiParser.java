package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.EventSchemaNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class AsyncApiParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_FILENAMES = List.of(
            "asyncapi.yaml", "asyncapi.yml", "asyncapi.json");

    private static final List<String> CANDIDATE_DIRECTORIES = List.of(
            "src/main/resources", "src/main/resources/api", "api", "docs", "spec",
            "src/main/resources/asyncapi");

    private static final int MAX_SCHEMAS_PER_FILE = 2000;

    @Override
    public String name() {
        return "AsyncApiParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findAsyncApiFiles(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, EventSchemaNode> schemasById = new LinkedHashMap<>();

        for (Path file : findAsyncApiFiles(context.projectPath())) {
            try {
                JsonNode root = readSpec(file);
                if (!isAsyncApi(root)) continue;
                String relativePath = context.projectPath().relativize(file).toString();
                extractChannels(root, context, relativePath, schemasById);
            } catch (IOException | RuntimeException e) {
                log.debug("AsyncApiParser skipped {}: {}", file, e.getMessage());
            }
        }

        attachEventSchemas(context.projectNode(), schemasById.values());
        log.info("AsyncApiParser ingested {} event schemas for project={}",
                schemasById.size(), context.projectId());
        return ParseResult.of(Map.of(
                "specs", findAsyncApiFiles(context.projectPath()).size(),
                "eventSchemas", schemasById.size()));
    }

    private List<Path> findAsyncApiFiles(Path projectPath) {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (String dir : CANDIDATE_DIRECTORIES) {
            Path resolved = projectPath.resolve(dir);
            if (!Files.isDirectory(resolved)) continue;
            scanForCandidates(projectPath, resolved).forEach(unique::add);
        }
        return new ArrayList<>(unique);
    }

    private List<Path> scanForCandidates(Path projectPath, Path dir) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(projectPath, dir, 3)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> CANDIDATE_FILENAMES.contains(p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private JsonNode readSpec(Path file) throws IOException {
        ObjectMapper mapper = file.getFileName().toString().toLowerCase().endsWith(".json")
                ? JSON_MAPPER : YAML_MAPPER;
        return mapper.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                file, IngestPathFilter.MAX_PARSE_BYTES));
    }

    private boolean isAsyncApi(JsonNode root) {
        return root.has("asyncapi");
    }

    private void extractChannels(JsonNode root, IngestionContext context, String relativePath,
                                  Map<String, EventSchemaNode> schemasById) {
        JsonNode channels = root.path("channels");
        if (!channels.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = channels.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String channelName = entry.getKey();
            JsonNode channel = entry.getValue();
            extractMessages(channel.path("messages"), channelName, "any", relativePath, context, schemasById);
            extractOperation(channel, channelName, "publish", relativePath, context, schemasById);
            extractOperation(channel, channelName, "subscribe", relativePath, context, schemasById);
        }
    }

    private void extractOperation(JsonNode channel, String channelName, String op, String relativePath,
                                   IngestionContext context, Map<String, EventSchemaNode> schemasById) {
        JsonNode opNode = channel.path(op);
        if (opNode.isMissingNode()) return;
        JsonNode message = opNode.path("message");
        if (message.isMissingNode()) return;
        addSchema(channelName, op, message, relativePath, context, schemasById);
    }

    private void extractMessages(JsonNode messages, String channelName, String op, String relativePath,
                                  IngestionContext context, Map<String, EventSchemaNode> schemasById) {
        if (!messages.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = messages.fields();
        while (it.hasNext()) {
            addSchema(channelName, op, it.next().getValue(), relativePath, context, schemasById);
        }
    }

    private void addSchema(String channelName, String op, JsonNode message, String relativePath,
                           IngestionContext context, Map<String, EventSchemaNode> schemasById) {
        if (schemasById.size() >= MAX_SCHEMAS_PER_FILE) return;
        String messageName = message.path("name").asText(message.path("title").asText("payload"));
        EventSchemaNode node = new EventSchemaNode();
        node.setId(context.projectId() + ":eventSchema:" + channelName + ":" + op + ":" + messageName);
        node.setProjectId(context.projectId());
        node.setChannelName(channelName);
        node.setMessageName(messageName);
        node.setOperation(op.toUpperCase());
        node.setContentType(message.path("contentType").asText("application/json"));
        node.setSource(relativePath);
        node.setPayloadFields(extractPayloadFields(message.path("payload")));
        schemasById.putIfAbsent(node.getId(), node);
    }

    private List<String> extractPayloadFields(JsonNode payload) {
        if (!payload.isObject()) return List.of();
        JsonNode properties = payload.path("properties");
        if (!properties.isObject()) return List.of();
        List<String> fields = new ArrayList<>();
        Iterator<String> it = properties.fieldNames();
        while (it.hasNext()) fields.add(it.next());
        return fields;
    }

    private void attachEventSchemas(ProjectNode projectNode, Iterable<EventSchemaNode> schemas) {
        Set<String> existing = new HashSet<>();
        projectNode.getEventSchemas().forEach(s -> existing.add(s.getId()));
        for (EventSchemaNode schema : schemas) {
            if (existing.add(schema.getId())) {
                projectNode.getEventSchemas().add(schema);
            }
        }
    }
}
