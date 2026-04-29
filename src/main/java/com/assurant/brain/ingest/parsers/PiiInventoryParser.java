package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.PiiTagNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class PiiInventoryParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_PATHS = List.of(
            "ops/pii-inventory.json",
            "ops/privacy/dsar-export.json",
            "ops/privacy/pii.json",
            "docs/ops/pii-inventory.json");

    private static final int MAX_TAGS_PER_FILE = 500;

    @Override
    public String name() {
        return "PiiInventoryParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        Map<String, PiiTagNode> tagsById = new LinkedHashMap<>();
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode entries = root.isArray() ? root
                    : root.has("entries") ? root.path("entries")
                    : root.path("dataElements");
            if (!entries.isArray()) {
                log.debug("PiiInventoryParser found no array of entries in {}", file);
                return ParseResult.empty();
            }
            for (JsonNode entry : entries) {
                if (tagsById.size() >= MAX_TAGS_PER_FILE) break;
                buildTag(entry, context, tagsById);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("PiiInventoryParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        attachTags(context.projectNode(), tagsById.values());
        log.info("PiiInventoryParser ingested {} PII tags for project={}",
                tagsById.size(), context.projectId());
        return ParseResult.of(Map.of("piiTags", tagsById.size()));
    }

    private void buildTag(JsonNode entry, IngestionContext context, Map<String, PiiTagNode> tagsById) {
        String category = StringUtils.firstNonBlank(
                entry.path("category").asText(null),
                entry.path("piiCategory").asText(null),
                entry.path("dataCategory").asText(""));
        String targetType = StringUtils.firstNonBlank(
                entry.path("targetType").asText(null),
                entry.path("entityType").asText("COLUMN"));
        String targetId = StringUtils.firstNonBlank(
                entry.path("target").asText(null),
                entry.path("column").asText(null),
                entry.path("path").asText(null),
                entry.path("identifier").asText(""));
        if (StringUtils.isBlank(targetId)) return;
        String sensitivity = StringUtils.firstNonBlank(
                entry.path("sensitivity").asText(null),
                entry.path("severity").asText("HIGH"));

        PiiTagNode node = new PiiTagNode();
        node.setId(context.projectId() + ":pii:" + targetType + ":" + targetId);
        node.setProjectId(context.projectId());
        node.setSource(StringUtils.upperCase(StringUtils.firstNonBlank(
                entry.path("source").asText(null), "DSAR")));
        node.setCategory(StringUtils.defaultIfBlank(category, "GENERIC_PII"));
        node.setTargetType(StringUtils.upperCase(targetType));
        node.setTargetIdentifier(targetId);
        node.setSensitivity(StringUtils.upperCase(sensitivity));
        tagsById.putIfAbsent(node.getId(), node);
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachTags(ProjectNode projectNode, Iterable<PiiTagNode> tags) {
        Set<String> existing = new HashSet<>();
        projectNode.getPiiTags().forEach(t -> existing.add(t.getId()));
        for (PiiTagNode tag : tags) {
            if (existing.add(tag.getId())) {
                projectNode.getPiiTags().add(tag);
            }
        }
    }
}
