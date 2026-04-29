package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.SLONode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Log4j2
@Component
public class OpenSloParser implements ArtifactParser {

    private static final List<String> SLO_DIRECTORIES = List.of(
            "slo",
            "slos",
            "openslo",
            "docs/slo"
    );

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Override
    public String name() {
        return "OpenSloParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return SLO_DIRECTORIES.stream().anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        List<SLONode> slos = new ArrayList<>();

        for (String sloDir : SLO_DIRECTORIES) {
            Path dir = context.projectPath().resolve(sloDir);
            if (!Files.isDirectory(dir)) continue;
            collectSlosFromDirectory(dir, context, slos);
        }

        if (!slos.isEmpty()) {
            context.projectNode().getSlos().addAll(slos);
            log.info("OpenSloParser ingested {} SLOs for project={}", slos.size(), context.projectId());
        }
        return ParseResult.of(Map.of("slos", slos.size()));
    }

    private void collectSlosFromDirectory(Path dir, IngestionContext context, List<SLONode> slos) {
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> sloFiles = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".slo.yaml") || name.endsWith(".slo.yml")
                                || name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .toList();
            for (Path file : sloFiles) {
                try {
                    JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(file, IngestPathFilter.MAX_PARSE_BYTES));
                    if (!isOpenSlo(root)) continue;
                    SLONode slo = buildSlo(file, root, context);
                    slos.add(slo);
                } catch (IOException e) {
                    log.warn("Failed to read SLO file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk SLO directory {}: {}", dir, e.getMessage());
        }
    }

    private boolean isOpenSlo(JsonNode root) {
        String apiVersion = root.path("apiVersion").asText("");
        String kind = root.path("kind").asText("");
        return apiVersion.startsWith("openslo") || "SLO".equalsIgnoreCase(kind);
    }

    private SLONode buildSlo(Path file, JsonNode root, IngestionContext context) {
        String relativePath = context.projectPath().relativize(file).toString();
        JsonNode metadata = root.path("metadata");
        JsonNode spec = root.path("spec");

        String name = metadata.path("name").asText(file.getFileName().toString());
        String description = spec.path("description").asText("");
        String serviceName = spec.path("service").asText("");
        JsonNode firstObjective = spec.path("objectives").isArray() && spec.path("objectives").size() > 0
                ? spec.path("objectives").get(0)
                : spec;

        double target = firstObjective.path("target").asDouble(0.0);
        if (target == 0.0) {
            target = firstObjective.path("ratioMetric").path("target").asDouble(0.0);
        }
        String window = firstObjective.path("window").asText(spec.path("timeWindow").path("duration").asText(""));
        String indicator = StringUtils.defaultIfBlank(spec.path("indicator").path("metadata").path("name").asText(""),
                spec.path("indicatorRef").asText(""));

        SLONode slo = new SLONode();
        slo.setId(context.projectId() + ":slo:" + relativePath);
        slo.setProjectId(context.projectId());
        slo.setServiceName(serviceName);
        slo.setIndicatorType(indicator);
        slo.setTargetPercent(target * (target <= 1.0 ? 100.0 : 1.0));
        slo.setWindow(window);
        slo.setDescription(StringUtils.defaultIfBlank(description, name));
        slo.setSourceFile(relativePath);
        return slo;
    }
}
