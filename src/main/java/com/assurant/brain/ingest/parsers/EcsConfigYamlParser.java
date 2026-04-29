package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.EcsServiceConfigNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class EcsConfigYamlParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final List<String> CANDIDATE_PATHS = List.of(
            ".infra/config.yaml", ".infra/config.yml",
            "infra/config.yaml", "infra/config.yml",
            "cdk/config.yaml", "cdk/config.yml");

    @Override
    public String name() {
        return "EcsConfigYamlParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveConfigPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path configPath = resolveConfigPath(context.projectPath());
        if (configPath == null) return ParseResult.empty();

        EcsServiceConfigNode config;
        try {
            JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(configPath, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode ecs = firstNonMissingPath(root, "ecs", "service", "task");
            if (ecs.isMissingNode()) {
                log.debug("EcsConfigYamlParser found no ecs/service/task block in {}", configPath);
                return ParseResult.empty();
            }
            config = buildConfig(configPath, root, ecs, context);
        } catch (IOException e) {
            log.warn("EcsConfigYamlParser failed to read {}: {}", configPath, e.getMessage());
            return ParseResult.empty();
        }

        context.projectNode().setEcsServiceConfig(config);
        log.info("EcsConfigYamlParser ingested ECS config from {} for project={}",
                config.getSourceFile(), context.projectId());
        return ParseResult.of(Map.of(
                "cpu", StringUtils.defaultString(config.getCpu()),
                "memory", StringUtils.defaultString(config.getMemory()),
                "desiredCount", config.getDesiredCount(),
                "healthCheckPath", StringUtils.defaultString(config.getHealthCheckPath())));
    }

    private EcsServiceConfigNode buildConfig(Path configPath, JsonNode root, JsonNode ecs,
                                              IngestionContext context) {
        String relativePath = context.projectPath().relativize(configPath).toString();
        EcsServiceConfigNode node = new EcsServiceConfigNode();
        node.setId(context.projectId() + ":ecsConfig");
        node.setProjectId(context.projectId());
        node.setCpu(StringUtils.firstNonBlank(
                ecs.path("cpu").asText(null),
                root.path("cpu").asText(null),
                root.path("task").path("cpu").asText("")));
        node.setMemory(StringUtils.firstNonBlank(
                ecs.path("memory").asText(null),
                ecs.path("memory_mb").asText(null),
                root.path("memory").asText("")));
        node.setDesiredCount(ecs.path("desiredCount").asInt(
                ecs.path("desired_count").asInt(0)));
        node.setHealthCheckPath(StringUtils.firstNonBlank(
                ecs.path("health_check_path").asText(null),
                ecs.path("healthCheckPath").asText(null),
                root.path("health_check_path").asText("")));

        JsonNode scaling = firstNonMissingPath(ecs, "scaling", "auto_scaling", "autoscaling");
        if (!scaling.isMissingNode()) {
            node.setScalingPolicySummary(scaling.toString());
        }
        node.setSourceFile(relativePath);
        return node;
    }

    private Path resolveConfigPath(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private JsonNode firstNonMissingPath(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode candidate = root.path(path);
            if (!candidate.isMissingNode() && candidate.size() > 0) return candidate;
        }
        return root.path(paths[0]);
    }
}
