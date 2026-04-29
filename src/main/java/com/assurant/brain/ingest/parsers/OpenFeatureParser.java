package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.FeatureFlagNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class OpenFeatureParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> FLAGD_CANDIDATES = List.of(
            "src/main/resources/openfeature/flagd.json",
            "src/main/resources/flagd.json",
            "openfeature/flagd.json",
            "flagd.json");

    private static final List<String> LAUNCHDARKLY_CANDIDATES = List.of(
            "launchdarkly-export.json",
            ".launchdarkly/flags.json");

    @Override
    public String name() {
        return "OpenFeatureParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath(), FLAGD_CANDIDATES) != null
                || resolve(context.projectPath(), LAUNCHDARKLY_CANDIDATES) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, FeatureFlagNode> flagsById = new LinkedHashMap<>();

        Path flagd = resolve(context.projectPath(), FLAGD_CANDIDATES);
        if (flagd != null) {
            parseFlagd(flagd, context, flagsById);
        }
        Path launchdarkly = resolve(context.projectPath(), LAUNCHDARKLY_CANDIDATES);
        if (launchdarkly != null) {
            parseLaunchDarkly(launchdarkly, context, flagsById);
        }

        attachFlags(context.projectNode(), flagsById.values());
        log.info("OpenFeatureParser ingested {} feature flags for project={}",
                flagsById.size(), context.projectId());
        return ParseResult.of(Map.of("featureFlags", flagsById.size()));
    }

    private void parseFlagd(Path file, IngestionContext context, Map<String, FeatureFlagNode> flags) {
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode flagsNode = root.path("flags");
            if (!flagsNode.isObject()) return;
            Iterator<Map.Entry<String, JsonNode>> it = flagsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String name = entry.getKey();
                JsonNode flag = entry.getValue();
                String type = flag.path("variants").fieldNames().hasNext()
                        ? typeOfFirstVariant(flag.path("variants"))
                        : flag.path("type").asText("BOOLEAN");
                String defaultValue = flag.path("defaultVariant").asText(
                        flag.path("default").asText(""));
                String state = flag.path("state").asText("ENABLED");

                FeatureFlagNode node = new FeatureFlagNode();
                node.setId(context.projectId() + ":flag:" + name);
                node.setProjectId(context.projectId());
                node.setName(name);
                node.setSource("FLAGD");
                node.setFlagType(type);
                node.setDefaultValue(defaultValue);
                node.setRolloutStatus(state);
                flags.putIfAbsent(node.getId(), node);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("OpenFeatureParser skipped flagd {}: {}", file, e.getMessage());
        }
    }

    private void parseLaunchDarkly(Path file, IngestionContext context, Map<String, FeatureFlagNode> flags) {
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode items = root.isArray() ? root : root.path("items");
            if (!items.isArray()) return;
            for (JsonNode flag : items) {
                String name = flag.path("key").asText("");
                if (StringUtils.isBlank(name)) continue;

                FeatureFlagNode node = new FeatureFlagNode();
                node.setId(context.projectId() + ":flag:" + name);
                node.setProjectId(context.projectId());
                node.setName(name);
                node.setSource("LAUNCHDARKLY");
                node.setFlagType(flag.path("kind").asText("boolean").toUpperCase());
                node.setDefaultValue(flag.path("defaults").path("offVariation").asText(""));
                node.setRolloutStatus(flag.path("temporary").asBoolean(false) ? "TEMPORARY" : "PERMANENT");
                flags.putIfAbsent(node.getId(), node);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("OpenFeatureParser skipped LaunchDarkly export {}: {}", file, e.getMessage());
        }
    }

    private String typeOfFirstVariant(JsonNode variants) {
        Iterator<Map.Entry<String, JsonNode>> it = variants.fields();
        if (!it.hasNext()) return "BOOLEAN";
        JsonNode first = it.next().getValue();
        if (first.isBoolean()) return "BOOLEAN";
        if (first.isInt() || first.isLong() || first.isDouble()) return "NUMBER";
        return "STRING";
    }

    private Path resolve(Path projectPath, List<String> candidates) {
        for (String relative : candidates) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachFlags(ProjectNode projectNode, Iterable<FeatureFlagNode> flags) {
        Set<String> existing = new HashSet<>();
        projectNode.getFeatureFlags().forEach(f -> existing.add(f.getId()));
        for (FeatureFlagNode flag : flags) {
            if (existing.add(flag.getId())) {
                projectNode.getFeatureFlags().add(flag);
            }
        }
    }
}
