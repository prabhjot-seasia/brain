package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.LogPatternNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class CloudWatchLogsInsightsParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_PATHS = List.of(
            "ops/logs-insights.json",
            "ops/log-patterns.json",
            "ops/cloudwatch-logs.csv",
            "ops/log-patterns.csv",
            "docs/ops/log-patterns.json");

    private static final int MAX_PATTERNS_PER_FILE = 200;

    @Override
    public String name() {
        return "CloudWatchLogsInsightsParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        Map<String, LogPatternNode> patterns = new LinkedHashMap<>();
        try {
            String filename = file.getFileName().toString().toLowerCase();
            byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(file, IngestPathFilter.MAX_PARSE_BYTES);
            if (filename.endsWith(".json")) {
                parseJson(bytes, context, patterns);
            } else {
                parseCsv(new String(bytes, StandardCharsets.UTF_8), context, patterns);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("CloudWatchLogsInsightsParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        attachLogPatterns(context.projectNode(), patterns.values());
        log.info("CloudWatchLogsInsightsParser ingested {} log patterns for project={}",
                patterns.size(), context.projectId());
        return ParseResult.of(Map.of("logPatterns", patterns.size()));
    }

    private void parseJson(byte[] bytes, IngestionContext context, Map<String, LogPatternNode> patterns)
            throws IOException {
        JsonNode root = JSON_MAPPER.readTree(bytes);
        JsonNode results = root.isArray() ? root : root.path("results");
        if (!results.isArray()) return;
        for (JsonNode entry : results) {
            if (patterns.size() >= MAX_PATTERNS_PER_FILE) break;
            String message = StringUtils.firstNonBlank(
                    entry.path("@message").asText(null),
                    entry.path("message").asText(null),
                    entry.path("template").asText(""));
            String level = StringUtils.firstNonBlank(
                    entry.path("@logLevel").asText(null),
                    entry.path("level").asText(null),
                    inferLevel(message));
            long count = entry.path("count").asLong(entry.path("occurrences").asLong(1));
            buildAndPut(context, level, message, count, "CW_LOGS_JSON", patterns);
        }
    }

    private void parseCsv(String content, IngestionContext context, Map<String, LogPatternNode> patterns) {
        String[] lines = content.split("\\R");
        if (lines.length < 2) return;
        String[] header = splitCsv(lines[0]);
        int messageIdx = firstIndex(header, "@message", "message", "template");
        int levelIdx = firstIndex(header, "@logLevel", "level");
        int countIdx = firstIndex(header, "count", "occurrences");
        if (messageIdx < 0) return;

        for (int i = 1; i < lines.length && patterns.size() < MAX_PATTERNS_PER_FILE; i++) {
            String[] cols = splitCsv(lines[i]);
            if (cols.length <= messageIdx) continue;
            String message = cols[messageIdx].trim();
            if (StringUtils.isBlank(message)) continue;
            String level = levelIdx >= 0 && levelIdx < cols.length
                    ? cols[levelIdx].trim()
                    : inferLevel(message);
            long count = countIdx >= 0 && countIdx < cols.length
                    ? parseLong(cols[countIdx].trim()) : 1;
            buildAndPut(context, level, message, count, "CW_LOGS_CSV", patterns);
        }
    }

    private void buildAndPut(IngestionContext context, String level, String message, long count,
                              String source, Map<String, LogPatternNode> patterns) {
        String hash = sha256(message);
        LogPatternNode node = new LogPatternNode();
        node.setId(context.projectId() + ":logPattern:" + hash);
        node.setProjectId(context.projectId());
        node.setLevel(StringUtils.upperCase(StringUtils.defaultIfBlank(level, "UNKNOWN")));
        node.setTemplate(message.length() > 1000 ? message.substring(0, 1000) : message);
        node.setExemplarMessage(node.getTemplate());
        node.setOccurrences(count);
        node.setSource(source);
        node.setCapturedAt(OffsetDateTime.now().toString());
        patterns.putIfAbsent(node.getId(), node);
    }

    private String inferLevel(String message) {
        String upper = message.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("EXCEPTION")) return "ERROR";
        if (upper.contains("WARN")) return "WARN";
        if (upper.contains("INFO")) return "INFO";
        return "UNKNOWN";
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    }

    private int firstIndex(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachLogPatterns(ProjectNode projectNode, Iterable<LogPatternNode> patterns) {
        Set<String> existing = new HashSet<>();
        projectNode.getLogPatterns().forEach(p -> existing.add(p.getId()));
        for (LogPatternNode pattern : patterns) {
            if (existing.add(pattern.getId())) {
                projectNode.getLogPatterns().add(pattern);
            }
        }
    }
}
