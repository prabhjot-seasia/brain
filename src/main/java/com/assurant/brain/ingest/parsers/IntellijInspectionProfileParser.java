package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class IntellijInspectionProfileParser implements ArtifactParser {

    private static final Path PROFILES_DIR = Path.of(".idea", "inspectionProfiles");

    private static final Pattern INSPECTION_TOOL = Pattern.compile(
            "(?is)<inspection_tool\\s+([^>]+?)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"([^\"]*)\"");

    private static final double INSPECTION_TRUST_WEIGHT = 1.2;
    private static final int MAX_INSPECTIONS = 200;
    private static final int MAX_PROFILE_BYTES = 1_000_000;

    @Override
    public String name() {
        return "IntellijInspectionProfileParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        Path dir = context.projectPath().resolve(PROFILES_DIR);
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".xml") && Files.isRegularFile(p));
        } catch (IOException e) {
            log.debug("IntellijInspectionProfileParser supports() failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path dir = context.projectPath().resolve(PROFILES_DIR);
        Map<String, ConventionNode> conventions = new LinkedHashMap<>();
        int filesParsed = 0;

        for (Path file : listProfiles(dir)) {
            try {
                byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(file, MAX_PROFILE_BYTES);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String relativePath = context.projectPath().relativize(file).toString();
                extractInspections(content, relativePath, context, conventions);
                filesParsed++;
            } catch (IOException | RuntimeException e) {
                log.debug("IntellijInspectionProfileParser skipped {}: {}", file, e.getMessage());
            }
            if (conventions.size() >= MAX_INSPECTIONS) break;
        }

        if (!conventions.isEmpty()) {
            ProjectNode projectNode = context.projectNode();
            for (ConventionNode c : conventions.values()) projectNode.getConventions().add(c);
        }

        return ParseResult.of(Map.of(
                "intellijInspectionProfiles", filesParsed,
                "intellijInspections", conventions.size()));
    }

    private void extractInspections(String content, String sourceFile, IngestionContext context,
                                     Map<String, ConventionNode> conventions) {
        Matcher m = INSPECTION_TOOL.matcher(content);
        while (m.find() && conventions.size() < MAX_INSPECTIONS) {
            Map<String, String> attrs = parseAttributes(m.group(1));
            String name = attrs.get("class");
            if (name == null || name.isBlank()) continue;
            String enabled = attrs.getOrDefault("enabled", "true");
            if (!"true".equalsIgnoreCase(enabled)) continue;
            String level = attrs.getOrDefault("level", "WARNING").toUpperCase();
            String rule = "IntelliJ inspection '" + name + "' enabled at " + level;
            putConvention(rule, "INTELLIJ_INSPECTION", sourceFile, context, conventions);
        }
    }

    private Map<String, String> parseAttributes(String fragment) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher attr = ATTRIBUTE.matcher(fragment);
        while (attr.find()) result.put(attr.group(1), attr.group(2));
        return result;
    }

    private void putConvention(String rule, String category, String sourceFile,
                                IngestionContext context, Map<String, ConventionNode> conventions) {
        String id = context.projectId() + ":" + category + ":" + Integer.toHexString(rule.hashCode());
        if (conventions.containsKey(id)) return;
        ConventionNode node = new ConventionNode();
        node.setRule(rule);
        node.setCategory(category);
        node.setProjectId(context.projectId());
        node.setSourceFile(sourceFile);
        node.setTrustWeight(INSPECTION_TRUST_WEIGHT);
        conventions.put(id, node);
    }

    private List<Path> listProfiles(Path dir) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".xml") && Files.isRegularFile(p))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException e) {
            log.debug("listProfiles() failed for {}: {}", dir, e.getMessage());
        }
        return result;
    }
}
