package com.assurant.brain.ingest.parsers.bdd;

import com.assurant.brain.graph.node.BddScenarioNode;
import com.assurant.brain.graph.repository.BddScenarioNodeRepository;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
@RequiredArgsConstructor
public class GherkinParser implements ArtifactParser {

    private static final long MAX_FILE_BYTES = 256_000;
    private static final int MAX_FILES = 1000;
    private static final Pattern APP_TAG_PATTERN = Pattern.compile("@app:([\\w-]+)");

    private final BddScenarioNodeRepository repository;

    @Override
    public String name() {
        return "GherkinParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        try {
            return IngestPathFilter.safeWalk(context.projectPath(), context.projectPath())
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().endsWith(".feature"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<BddScenarioNode> persisted = new ArrayList<>();
        List<String> tagSamples = new ArrayList<>();
        int filesScanned = 0;
        int scenariosParsed = 0;
        int scenariosWithAppTag = 0;
        String fallbackCoverage = context.manifest() == null ? null : context.manifest().automationForProjectId();

        try {
            List<Path> featureFiles = IngestPathFilter.safeWalk(context.projectPath(), context.projectPath())
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".feature"))
                    .limit(MAX_FILES)
                    .toList();

            for (Path file : featureFiles) {
                byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(file, MAX_FILE_BYTES);
                if (bytes == null) continue;
                String content = new String(bytes, StandardCharsets.UTF_8);
                String relativePath = context.projectPath().relativize(file).toString();
                List<BddScenarioNode> scenarios = parseFeature(context.projectId(), relativePath, content,
                        fallbackCoverage);
                for (BddScenarioNode scenario : scenarios) {
                    BddScenarioNode saved = repository.save(scenario);
                    persisted.add(saved);
                    scenariosParsed++;
                    if (saved.getCoversProjectId() != null && !saved.getCoversProjectId().isBlank()) {
                        scenariosWithAppTag++;
                    }
                    tagSamples.addAll(saved.getTags());
                }
                filesScanned++;
            }
        } catch (IOException e) {
            log.warn("GherkinParser: walk failed for project={}: {}", context.projectId(), e.getMessage());
        }

        stats.put("featureFilesScanned", filesScanned);
        stats.put("scenariosParsed", scenariosParsed);
        stats.put("scenariosWithAppTag", scenariosWithAppTag);
        stats.put("hasAppTagConvention", scenariosParsed > 0
                && scenariosWithAppTag * 100 / scenariosParsed >= 80);
        stats.put("scenarios", persisted.size());
        return ParseResult.of(stats);
    }

    private List<BddScenarioNode> parseFeature(String projectId, String relativePath,
                                                 String content, String fallbackCoverage) {
        List<BddScenarioNode> out = new ArrayList<>();
        String featureTitle = "";
        List<String> currentTags = new ArrayList<>();
        BddScenarioNode current = null;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("@")) {
                currentTags.addAll(extractTags(line));
                continue;
            }
            if (line.startsWith("Feature:")) {
                featureTitle = line.substring("Feature:".length()).strip();
                continue;
            }
            if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                if (current != null) out.add(current);
                String scenarioTitle = line.startsWith("Scenario Outline:")
                        ? line.substring("Scenario Outline:".length()).strip()
                        : line.substring("Scenario:".length()).strip();
                current = newScenario(projectId, relativePath, featureTitle, scenarioTitle,
                        new ArrayList<>(currentTags), fallbackCoverage);
                currentTags.clear();
                continue;
            }
            if (current != null && (line.startsWith("Given ") || line.startsWith("When ")
                    || line.startsWith("Then ") || line.startsWith("And ") || line.startsWith("But "))) {
                current.getSteps().add(line);
            }
        }
        if (current != null) out.add(current);
        return out;
    }

    private BddScenarioNode newScenario(String projectId, String relativePath, String featureTitle,
                                          String scenarioTitle, List<String> tags, String fallbackCoverage) {
        BddScenarioNode node = new BddScenarioNode();
        node.setId(stableId(projectId, relativePath, scenarioTitle));
        node.setProjectId(projectId);
        node.setFeatureFile(relativePath);
        node.setFeatureTitle(featureTitle);
        node.setScenarioTitle(scenarioTitle);
        node.setTags(tags);
        node.setSteps(new ArrayList<>());
        node.setCoversProjectId(extractAppTag(tags).orElse(fallbackCoverage));
        return node;
    }

    private List<String> extractTags(String tagLine) {
        List<String> tags = new ArrayList<>();
        for (String token : tagLine.split("\\s+")) {
            if (token.startsWith("@")) tags.add(token);
        }
        return tags;
    }

    private java.util.Optional<String> extractAppTag(List<String> tags) {
        for (String tag : tags) {
            Matcher m = APP_TAG_PATTERN.matcher(tag);
            if (m.matches()) return java.util.Optional.of(m.group(1));
        }
        return java.util.Optional.empty();
    }

    private String stableId(String projectId, String relativePath, String scenarioTitle) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = projectId + "|" + relativePath + "|" + scenarioTitle;
            byte[] hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "bdd-" + HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
