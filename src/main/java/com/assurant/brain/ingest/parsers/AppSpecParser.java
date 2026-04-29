package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.DeployHookNode;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class AppSpecParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final List<String> CANDIDATE_PATHS = List.of("appspec.yml", "appspec.yaml");

    @Override
    public String name() {
        return "AppSpecParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveAppSpecPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path appSpecPath = resolveAppSpecPath(context.projectPath());
        if (appSpecPath == null) return ParseResult.empty();

        Map<String, DeployHookNode> hooksById = new LinkedHashMap<>();
        try {
            JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(appSpecPath, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode hooks = root.path("hooks");
            if (!hooks.isObject()) return ParseResult.empty();

            String relativePath = context.projectPath().relativize(appSpecPath).toString();
            Iterator<Map.Entry<String, JsonNode>> phaseIt = hooks.fields();
            while (phaseIt.hasNext()) {
                Map.Entry<String, JsonNode> phaseEntry = phaseIt.next();
                String phase = phaseEntry.getKey();
                JsonNode steps = phaseEntry.getValue();
                if (!steps.isArray()) continue;
                for (JsonNode step : steps) {
                    String location = step.path("location").asText("");
                    if (location.isBlank()) continue;
                    String id = context.projectId() + ":deployHook:" + phase + ":" + location;
                    hooksById.computeIfAbsent(id, k -> {
                        DeployHookNode hook = new DeployHookNode();
                        hook.setId(id);
                        hook.setProjectId(context.projectId());
                        hook.setPhase(phase);
                        hook.setScriptPath(location);
                        hook.setRunas(step.path("runas").asText(""));
                        hook.setSourceFile(relativePath);
                        return hook;
                    });
                }
            }
        } catch (IOException e) {
            log.warn("AppSpecParser failed to read {}: {}", appSpecPath, e.getMessage());
            return ParseResult.empty();
        }

        attachHooks(context.projectNode(), hooksById.values());
        log.info("AppSpecParser ingested {} deploy hooks for project={}",
                hooksById.size(), context.projectId());
        return ParseResult.of(Map.of("deployHooks", hooksById.size()));
    }

    private Path resolveAppSpecPath(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachHooks(ProjectNode projectNode, Iterable<DeployHookNode> hooks) {
        Set<String> existing = new HashSet<>();
        projectNode.getDeployHooks().forEach(h -> existing.add(h.getId()));
        for (DeployHookNode hook : hooks) {
            if (existing.add(hook.getId())) {
                projectNode.getDeployHooks().add(hook);
            }
        }
    }
}
