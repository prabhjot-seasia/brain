package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.RuntimeServiceEdgeNode;
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
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class XRayServiceGraphParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_PATHS = List.of(
            "ops/xray-service-graph.json",
            "ops/observability/xray.json",
            "ops/xray.json",
            "docs/ops/xray-service-graph.json");

    private static final int MAX_EDGES_PER_PROJECT = 500;

    @Override
    public String name() {
        return "XRayServiceGraphParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        Map<String, RuntimeServiceEdgeNode> edgesById = new LinkedHashMap<>();
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode services = root.path("Services");
            if (!services.isArray()) {
                services = root.path("services");
            }
            if (!services.isArray()) {
                log.debug("XRayServiceGraphParser found no Services array in {}", file);
                return ParseResult.empty();
            }
            for (JsonNode service : services) {
                if (edgesById.size() >= MAX_EDGES_PER_PROJECT) break;
                String fromName = StringUtils.firstNonBlank(
                        service.path("Name").asText(null),
                        service.path("name").asText(null),
                        "");
                JsonNode edges = service.path("Edges");
                if (!edges.isArray()) edges = service.path("edges");
                if (!edges.isArray()) continue;
                int sampledHours = root.path("sampledFromHours").asInt(168);
                for (JsonNode edge : edges) {
                    if (edgesById.size() >= MAX_EDGES_PER_PROJECT) break;
                    String toName = StringUtils.firstNonBlank(
                            edge.path("ReferenceName").asText(null),
                            edge.path("Name").asText(null),
                            edge.path("toServiceName").asText(""));
                    if (StringUtils.isBlank(fromName) || StringUtils.isBlank(toName)) continue;

                    RuntimeServiceEdgeNode node = new RuntimeServiceEdgeNode();
                    node.setId(context.projectId() + ":runtimeEdge:" + fromName + "->" + toName);
                    node.setProjectId(context.projectId());
                    node.setFromServiceName(fromName);
                    node.setToServiceName(toName);
                    node.setFrequency(edge.path("SummaryStatistics").path("TotalCount").asLong(
                            edge.path("frequency").asLong(0)));
                    node.setP50LatencyMs(edge.path("SummaryStatistics").path("AverageResponseTime").asDouble(
                            edge.path("p50LatencyMs").asDouble(0.0)));
                    node.setP99LatencyMs(edge.path("p99LatencyMs").asDouble(0.0));
                    node.setErrorRate(computeErrorRate(edge));
                    node.setSource("AWS_XRAY");
                    node.setSampledFromHours(sampledHours);
                    node.setCapturedAt(OffsetDateTime.now().toString());
                    edgesById.putIfAbsent(node.getId(), node);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("XRayServiceGraphParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        attachRuntimeEdges(context.projectNode(), edgesById.values());
        log.info("XRayServiceGraphParser ingested {} runtime service edges for project={}",
                edgesById.size(), context.projectId());
        return ParseResult.of(Map.of("runtimeEdges", edgesById.size()));
    }

    private double computeErrorRate(JsonNode edge) {
        JsonNode summary = edge.path("SummaryStatistics");
        long total = summary.path("TotalCount").asLong(0);
        if (total == 0) return edge.path("errorRate").asDouble(0.0);
        long errors = summary.path("ErrorStatistics").path("TotalCount").asLong(0)
                + summary.path("FaultStatistics").path("TotalCount").asLong(0);
        return (double) errors / total;
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachRuntimeEdges(ProjectNode projectNode, Iterable<RuntimeServiceEdgeNode> edges) {
        Set<String> existing = new HashSet<>();
        projectNode.getRuntimeServiceEdges().forEach(e -> existing.add(e.getId()));
        for (RuntimeServiceEdgeNode edge : edges) {
            if (existing.add(edge.getId())) {
                projectNode.getRuntimeServiceEdges().add(edge);
            }
        }
    }
}
