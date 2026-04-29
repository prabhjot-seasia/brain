package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ChaosExperimentNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class FisExperimentParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> SCAN_DIRECTORIES = List.of(
            ".infra/fis",
            "infra/fis",
            "fis",
            "chaos");

    @Override
    public String name() {
        return "FisExperimentParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return SCAN_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ChaosExperimentNode> experimentsById = new LinkedHashMap<>();

        for (String dir : SCAN_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(root, context, experimentsById);
        }

        attachExperiments(context.projectNode(), experimentsById.values());
        log.info("FisExperimentParser ingested {} chaos experiments for project={}",
                experimentsById.size(), context.projectId());
        return ParseResult.of(Map.of("experiments", experimentsById.size()));
    }

    private void walk(Path root, IngestionContext context, Map<String, ChaosExperimentNode> experiments) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), root)) {
            List<Path> jsons = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : jsons) {
                processFile(file, context, experiments);
            }
        } catch (IOException e) {
            log.warn("FisExperimentParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, IngestionContext context, Map<String, ChaosExperimentNode> experiments) {
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode template = root.has("experimentTemplate") ? root.path("experimentTemplate") : root;
            String name = StringUtils.firstNonBlank(
                    template.path("description").asText(null),
                    template.path("name").asText(null),
                    file.getFileName().toString());
            String arn = template.path("arn").asText("");

            ChaosExperimentNode node = new ChaosExperimentNode();
            node.setId(context.projectId() + ":chaos:" + name.toLowerCase().replaceAll("[^a-z0-9-]", "-"));
            node.setProjectId(context.projectId());
            node.setName(name);
            node.setTemplateArn(arn);
            node.setSource("AWS_FIS");
            node.setKnownFailureModes(extractFailureModes(template.path("actions")));
            node.setTargetServices(extractTargetServices(template.path("targets")));
            experiments.putIfAbsent(node.getId(), node);
        } catch (IOException | RuntimeException e) {
            log.debug("FisExperimentParser skipped {}: {}", file, e.getMessage());
        }
    }

    private List<String> extractFailureModes(JsonNode actions) {
        Set<String> modes = new LinkedHashSet<>();
        if (!actions.isObject()) return List.of();
        Iterator<Map.Entry<String, JsonNode>> it = actions.fields();
        while (it.hasNext()) {
            String actionId = it.next().getValue().path("actionId").asText("");
            if (!actionId.isBlank()) modes.add(actionId);
        }
        return List.copyOf(modes);
    }

    private List<String> extractTargetServices(JsonNode targets) {
        Set<String> services = new LinkedHashSet<>();
        if (!targets.isObject()) return List.of();
        Iterator<Map.Entry<String, JsonNode>> it = targets.fields();
        while (it.hasNext()) {
            String resourceType = it.next().getValue().path("resourceType").asText("");
            if (!resourceType.isBlank()) services.add(resourceType);
        }
        return List.copyOf(services);
    }

    private void attachExperiments(ProjectNode projectNode, Iterable<ChaosExperimentNode> experiments) {
        Set<String> existing = new HashSet<>();
        projectNode.getChaosExperiments().forEach(c -> existing.add(c.getId()));
        for (ChaosExperimentNode experiment : experiments) {
            if (existing.add(experiment.getId())) {
                projectNode.getChaosExperiments().add(experiment);
            }
        }
    }
}
