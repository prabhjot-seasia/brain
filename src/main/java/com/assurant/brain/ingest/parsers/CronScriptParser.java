package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.ScheduledScriptNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class CronScriptParser implements ArtifactParser {

    private static final List<String> SCAN_DIRECTORIES = List.of(
            "src/cronjobs",
            "src/main/resources/cronjobs",
            "cronjobs",
            "scripts/cron",
            "common/migration",
            "common/front-migration");

    private static final Pattern CRON_HINT = Pattern.compile(
            "(?im)^#\\s*(?:cron|schedule)\\s*[:=]\\s*([0-9*/,\\- ]{9,})\\s*$");

    @Override
    public String name() {
        return "CronScriptParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return SCAN_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ScheduledScriptNode> nodes = new LinkedHashMap<>();
        for (String dir : SCAN_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(root, context, nodes);
        }
        attachScheduledScripts(context.projectNode(), nodes.values());
        log.info("CronScriptParser ingested {} scheduled scripts for project={}",
                nodes.size(), context.projectId());
        return ParseResult.of(Map.of("scheduledScripts", nodes.size()));
    }

    private void walk(Path root, IngestionContext context, Map<String, ScheduledScriptNode> nodes) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> scripts = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".sh") || name.endsWith(".sql") || name.endsWith(".py");
                    })
                    .toList();
            for (Path script : scripts) {
                buildNode(script, context, nodes);
            }
        } catch (IOException e) {
            log.debug("CronScriptParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void buildNode(Path script, IngestionContext context, Map<String, ScheduledScriptNode> nodes) {
        String relativePath = context.projectPath().relativize(script).toString();
        String type = scriptType(script.getFileName().toString());
        String cron = extractCronHint(script);
        String purpose = inferPurpose(script.getFileName().toString());

        String id = context.projectId() + ":cron:" + relativePath;
        nodes.computeIfAbsent(id, k -> {
            ScheduledScriptNode node = new ScheduledScriptNode();
            node.setId(id);
            node.setProjectId(context.projectId());
            node.setPath(relativePath);
            node.setScriptType(type);
            node.setCronExpression(cron);
            node.setPurpose(purpose);
            return node;
        });
    }

    private String scriptType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".sh")) return "SHELL";
        if (lower.endsWith(".sql")) return "SQL";
        if (lower.endsWith(".py")) return "PYTHON";
        return "OTHER";
    }

    private String extractCronHint(Path script) {
        if (!IngestPathFilter.isParseSizeWithinLimit(script, IngestPathFilter.MAX_SCRIPT_BYTES)) {
            return "";
        }
        try {
            String content = Files.readString(script);
            Matcher m = CRON_HINT.matcher(content);
            return m.find() ? m.group(1).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private String inferPurpose(String filename) {
        String stem = filename.replaceFirst("\\.[^.]+$", "").toLowerCase();
        if (stem.contains("migration")) return "MIGRATION";
        if (stem.contains("feed")) return "FEED";
        if (stem.contains("report")) return "REPORT";
        if (stem.contains("cleanup") || stem.contains("purge")) return "CLEANUP";
        return "OTHER";
    }

    private void attachScheduledScripts(ProjectNode projectNode, Iterable<ScheduledScriptNode> nodes) {
        Set<String> existing = new HashSet<>();
        projectNode.getScheduledScripts().forEach(s -> existing.add(s.getId()));
        for (ScheduledScriptNode node : nodes) {
            if (existing.add(node.getId())) {
                projectNode.getScheduledScripts().add(node);
            }
        }
    }
}
