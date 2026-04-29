package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.WorkflowNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class GitHubActionsWorkflowParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String WORKFLOWS_DIR = ".github/workflows";

    @Override
    public String name() {
        return "GitHubActionsWorkflowParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return Files.isDirectory(context.projectPath().resolve(WORKFLOWS_DIR));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path root = context.projectPath().resolve(WORKFLOWS_DIR);
        if (!Files.isDirectory(root)) return ParseResult.empty();

        Map<String, WorkflowNode> nodesById = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> files = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".yml") || n.endsWith(".yaml");
                    }).toList();
            for (Path file : files) {
                processFile(file, context, nodesById);
            }
        } catch (IOException e) {
            log.warn("GitHubActionsWorkflowParser failed to walk {}: {}", root, e.getMessage());
        }

        attachWorkflows(context.projectNode(), nodesById.values());
        log.info("GitHubActionsWorkflowParser ingested {} workflows for project={}",
                nodesById.size(), context.projectId());
        return ParseResult.of(Map.of("workflows", nodesById.size()));
    }

    private void processFile(Path file, IngestionContext context, Map<String, WorkflowNode> nodesById) {
        try {
            JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(file, IngestPathFilter.MAX_PARSE_BYTES));
            String relativePath = context.projectPath().relativize(file).toString();
            String name = root.path("name").asText(file.getFileName().toString());

            WorkflowNode node = new WorkflowNode();
            node.setId(context.projectId() + ":workflow:" + relativePath);
            node.setProjectId(context.projectId());
            node.setName(name);
            node.setPath(relativePath);
            node.getTriggers().addAll(extractTriggers(root));
            node.getMatrixDimensions().addAll(extractMatrixDimensions(root));
            node.getUsesReusableWorkflows().addAll(extractReusableWorkflowUses(root));
            nodesById.put(node.getId(), node);
        } catch (IOException e) {
            log.debug("GitHubActionsWorkflowParser skipped {}: {}", file, e.getMessage());
        }
    }

    private List<String> extractTriggers(JsonNode root) {
        List<String> triggers = new ArrayList<>();
        JsonNode on = root.path("on");
        if (on.isTextual()) triggers.add(on.asText());
        else if (on.isArray()) on.forEach(t -> triggers.add(t.asText()));
        else if (on.isObject()) on.fieldNames().forEachRemaining(triggers::add);
        return triggers;
    }

    private List<String> extractMatrixDimensions(JsonNode root) {
        List<String> dims = new ArrayList<>();
        JsonNode jobs = root.path("jobs");
        if (!jobs.isObject()) return dims;
        Iterator<Map.Entry<String, JsonNode>> it = jobs.fields();
        while (it.hasNext()) {
            JsonNode strategy = it.next().getValue().path("strategy").path("matrix");
            if (!strategy.isObject()) continue;
            strategy.fieldNames().forEachRemaining(dims::add);
        }
        return dims;
    }

    private List<String> extractReusableWorkflowUses(JsonNode root) {
        List<String> uses = new ArrayList<>();
        JsonNode jobs = root.path("jobs");
        if (!jobs.isObject()) return uses;
        Iterator<Map.Entry<String, JsonNode>> it = jobs.fields();
        while (it.hasNext()) {
            String useValue = it.next().getValue().path("uses").asText("");
            if (!useValue.isBlank()) uses.add(useValue);
        }
        return uses;
    }

    private void attachWorkflows(ProjectNode projectNode, Iterable<WorkflowNode> workflows) {
        Set<String> existing = new HashSet<>();
        projectNode.getCiWorkflows().forEach(w -> existing.add(w.getId()));
        for (WorkflowNode workflow : workflows) {
            if (existing.add(workflow.getId())) {
                projectNode.getCiWorkflows().add(workflow);
            }
        }
    }
}
