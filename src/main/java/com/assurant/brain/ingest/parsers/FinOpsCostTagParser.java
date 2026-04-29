package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.CostTagNode;
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
public class FinOpsCostTagParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_PATHS = List.of(
            "ops/cost-explorer.json",
            "ops/finops/cost-tags.json",
            "ops/aws-cost.json",
            "docs/ops/cost-explorer.json");

    private static final int MAX_TAGS_PER_FILE = 200;

    @Override
    public String name() {
        return "FinOpsCostTagParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        Map<String, CostTagNode> tagsById = new LinkedHashMap<>();
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode results = root.has("ResultsByTime") ? root.path("ResultsByTime") : root;
            if (results.isArray()) {
                for (JsonNode period : results) {
                    parseGroups(period, context, tagsById);
                    if (tagsById.size() >= MAX_TAGS_PER_FILE) break;
                }
            } else if (root.isArray()) {
                for (JsonNode entry : root) {
                    parseFlatEntry(entry, context, tagsById);
                    if (tagsById.size() >= MAX_TAGS_PER_FILE) break;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("FinOpsCostTagParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        attachCostTags(context.projectNode(), tagsById.values());
        log.info("FinOpsCostTagParser ingested {} cost tags for project={}",
                tagsById.size(), context.projectId());
        return ParseResult.of(Map.of("costTags", tagsById.size()));
    }

    private void parseGroups(JsonNode period, IngestionContext context, Map<String, CostTagNode> tagsById) {
        String billingPeriod = StringUtils.firstNonBlank(
                period.path("TimePeriod").path("Start").asText(null),
                period.path("TimePeriod").path("Period").asText(""));
        JsonNode groups = period.path("Groups");
        if (!groups.isArray()) return;
        for (JsonNode group : groups) {
            if (tagsById.size() >= MAX_TAGS_PER_FILE) return;
            JsonNode keys = group.path("Keys");
            if (!keys.isArray() || keys.isEmpty()) continue;
            String groupKey = keys.get(0).asText("");
            String tagKey = "";
            String tagValue = groupKey;
            if (groupKey.startsWith("user:") || groupKey.startsWith("aws:")) {
                int dollar = groupKey.indexOf('$');
                if (dollar <= 0) continue;
                tagKey = groupKey.substring(0, dollar);
                tagValue = groupKey.substring(dollar + 1);
            }
            double amount = group.path("Metrics").path("UnblendedCost").path("Amount").asDouble(0.0);
            String unit = group.path("Metrics").path("UnblendedCost").path("Unit").asText("USD");
            put(tagKey, tagValue, amount, unit, billingPeriod, context, tagsById);
        }
    }

    private void parseFlatEntry(JsonNode entry, IngestionContext context, Map<String, CostTagNode> tagsById) {
        String tagKey = entry.path("tagKey").asText("");
        String tagValue = entry.path("tagValue").asText("");
        if (StringUtils.isBlank(tagValue)) return;
        double amount = entry.path("amount").asDouble(entry.path("monthlyCostUsd").asDouble(0.0));
        String unit = entry.path("currency").asText("USD");
        String period = entry.path("billingPeriod").asText("");
        put(tagKey, tagValue, amount, unit, period, context, tagsById);
    }

    private void put(String tagKey, String tagValue, double amount, String unit, String period,
                     IngestionContext context, Map<String, CostTagNode> tagsById) {
        if (StringUtils.isBlank(tagValue)) return;
        CostTagNode node = new CostTagNode();
        String safeKey = StringUtils.defaultIfBlank(tagKey, "untagged");
        node.setId(context.projectId() + ":costTag:" + safeKey + ":" + tagValue);
        node.setProjectId(context.projectId());
        node.setTagKey(safeKey);
        node.setTagValue(tagValue);
        node.setMonthlyCostUsd(amount);
        node.setCurrency(unit);
        node.setBillingPeriod(period);
        tagsById.putIfAbsent(node.getId(), node);
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachCostTags(ProjectNode projectNode, Iterable<CostTagNode> tags) {
        Set<String> existing = new HashSet<>();
        projectNode.getCostTags().forEach(t -> existing.add(t.getId()));
        for (CostTagNode tag : tags) {
            if (existing.add(tag.getId())) {
                projectNode.getCostTags().add(tag);
            }
        }
    }
}
