package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
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

@Log4j2
@Component
public class LogConfigParser implements ArtifactParser {

    private static final List<String> LOG_CONFIG_PATHS = List.of(
            "src/main/resources/log4j2.xml",
            "src/main/resources/log4j2.yaml",
            "src/main/resources/log4j2.yml",
            "src/main/resources/log4j2-spring.xml",
            "src/main/resources/logback.xml",
            "src/main/resources/logback-spring.xml");

    private static final Pattern PATTERN_LAYOUT = Pattern.compile(
            "(?im)<PatternLayout[^>]*pattern\\s*=\\s*\"([^\"]+)\"|<pattern>([^<]+)</pattern>");
    private static final Pattern LOGGER_LEVEL = Pattern.compile(
            "(?im)<Logger[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*level\\s*=\\s*\"([^\"]+)\"|"
                    + "<logger[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*level\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ROOT_LEVEL = Pattern.compile(
            "(?im)<Root[^>]*level\\s*=\\s*\"([^\"]+)\"|<root[^>]*level\\s*=\\s*\"([^\"]+)\"");

    private static final double LOG_TRUST_WEIGHT = 1.5;
    private static final int MAX_LOGGER_OVERRIDES = 30;
    private static final int MAX_PATTERN_MATCHES = 50;
    private static final int MAX_CONFIG_CHARS = 1_000_000;

    @Override
    public String name() {
        return "LogConfigParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        for (String relative : LOG_CONFIG_PATHS) {
            Path candidate = context.projectPath().resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return true;
        }
        return false;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ConventionNode> conventionsById = new LinkedHashMap<>();

        for (Path file : resolveAll(context.projectPath())) {
            try {
                String content = new String(IngestPathFilter.readAllBytesIfWithinLimit(
                        file, IngestPathFilter.MAX_PARSE_BYTES), StandardCharsets.UTF_8);
                if (content.length() > MAX_CONFIG_CHARS) {
                    content = content.substring(0, MAX_CONFIG_CHARS);
                }
                String relativePath = context.projectPath().relativize(file).toString();
                extractPattern(content, relativePath, context, conventionsById);
                extractRootLevel(content, relativePath, context, conventionsById);
                extractLoggerOverrides(content, relativePath, context, conventionsById);
            } catch (IOException | RuntimeException e) {
                log.debug("LogConfigParser skipped {}: {}", file, e.getMessage());
            }
        }

        attachConventions(context.projectNode(), conventionsById.values());
        log.info("LogConfigParser ingested {} logging conventions for project={}",
                conventionsById.size(), context.projectId());
        return ParseResult.of(Map.of("loggingConventions", conventionsById.size()));
    }

    private void extractPattern(String content, String relativePath, IngestionContext context,
                                 Map<String, ConventionNode> conventions) {
        Matcher m = PATTERN_LAYOUT.matcher(content);
        int matched = 0;
        while (m.find() && matched < MAX_PATTERN_MATCHES) {
            String pattern = StringUtils.firstNonBlank(m.group(1), m.group(2));
            if (StringUtils.isBlank(pattern)) continue;
            String rule = "Log pattern: " + pattern.trim();
            putConvention(rule, "LOGGING", relativePath, context, conventions);
            matched++;
        }
    }

    private void extractRootLevel(String content, String relativePath, IngestionContext context,
                                   Map<String, ConventionNode> conventions) {
        Matcher m = ROOT_LEVEL.matcher(content);
        if (!m.find()) return;
        String level = StringUtils.firstNonBlank(m.group(1), m.group(2));
        if (StringUtils.isBlank(level)) return;
        putConvention("Root logger level: " + level.toUpperCase(),
                "LOGGING", relativePath, context, conventions);
    }

    private void extractLoggerOverrides(String content, String relativePath, IngestionContext context,
                                         Map<String, ConventionNode> conventions) {
        Matcher m = LOGGER_LEVEL.matcher(content);
        int seen = 0;
        while (m.find() && seen < MAX_LOGGER_OVERRIDES) {
            String name = StringUtils.firstNonBlank(m.group(1), m.group(3));
            String level = StringUtils.firstNonBlank(m.group(2), m.group(4));
            if (StringUtils.isBlank(name) || StringUtils.isBlank(level)) continue;
            putConvention("Logger '" + name + "' level: " + level.toUpperCase(),
                    "LOGGING", relativePath, context, conventions);
            seen++;
        }
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
        node.setTrustWeight(LOG_TRUST_WEIGHT);
        conventions.put(id, node);
    }

    private List<Path> resolveAll(Path projectPath) {
        List<Path> found = new ArrayList<>();
        for (String relative : LOG_CONFIG_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) found.add(candidate);
        }
        return found;
    }

    private void attachConventions(ProjectNode projectNode, Iterable<ConventionNode> conventions) {
        for (ConventionNode convention : conventions) {
            projectNode.getConventions().add(convention);
        }
    }
}
