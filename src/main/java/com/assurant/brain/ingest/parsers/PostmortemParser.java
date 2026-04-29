package com.assurant.brain.ingest.parsers;

import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestDocumentFactory;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
@RequiredArgsConstructor
public class PostmortemParser implements ArtifactParser {

    private static final List<String> POSTMORTEM_DIRECTORIES = List.of(
            "docs/postmortems",
            "docs/incidents",
            "postmortems",
            "incidents");

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "^(\\w[\\w-]*)\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern HEADING_TITLE_PATTERN = Pattern.compile(
            "^#\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern ROOT_CAUSE_SECTION = Pattern.compile(
            "(?is)#+\\s*(?:root\\s*cause|cause)\\s*\\n+([\\s\\S]*?)(?=\\n#+\\s|\\z)");
    private static final Pattern CLASS_REFERENCE = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]+(?:Service|Controller|Repository|Manager|Client|Handler|Listener|Job))\\b");

    private final IngestDocumentFactory documentFactory;

    @Override
    public String name() {
        return "PostmortemParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return POSTMORTEM_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        List<IncidentNode> incidents = new ArrayList<>();
        int chunksAdded = 0;

        for (String dir : POSTMORTEM_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            chunksAdded += parseDirectory(root, context, incidents);
        }

        if (!incidents.isEmpty()) {
            context.projectNode().getIncidents().addAll(incidents);
            log.info("PostmortemParser ingested {} incidents for project={}",
                    incidents.size(), context.projectId());
        }
        return ParseResult.of(Map.of(
                "incidents", incidents.size(),
                "chunks", chunksAdded));
    }

    private int parseDirectory(Path dir, IngestionContext context, List<IncidentNode> incidents) {
        int chunksAdded = 0;
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), dir)) {
            List<Path> markdownFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : markdownFiles) {
                try {
                    String content = Files.readString(file);
                    IncidentNode incident = buildIncident(file, content, context);
                    incidents.add(incident);
                    addChunk(file, content, context);
                    chunksAdded++;
                } catch (IOException e) {
                    log.warn("Failed to read postmortem file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk postmortem directory {}: {}", dir, e.getMessage());
        }
        return chunksAdded;
    }

    private IncidentNode buildIncident(Path file, String content, IngestionContext context) {
        Map<String, String> frontmatter = extractFrontmatter(content);
        String filename = file.getFileName().toString();
        String relativePath = context.projectPath().relativize(file).toString();

        IncidentNode incident = new IncidentNode();
        incident.setId(context.projectId() + ":incident:" + relativePath);
        incident.setProjectId(context.projectId());
        incident.setTitle(resolveTitle(filename, frontmatter, content));
        incident.setSeverity(frontmatter.getOrDefault("severity", "").toUpperCase());
        incident.setOccurredAt(frontmatter.getOrDefault("date",
                frontmatter.getOrDefault("occurred", "")));
        incident.setStatus(frontmatter.getOrDefault("status", "RESOLVED").toUpperCase());
        incident.setRootCauseSummary(extractRootCause(content));
        incident.setFilePath(relativePath);
        incident.setAffectedClassHints(extractClassHints(content));
        incident.setAffectedServices(parseList(frontmatter.get("services")));
        return incident;
    }

    private Map<String, String> extractFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) return result;
        Matcher kv = KEY_VALUE_PATTERN.matcher(m.group(1));
        while (kv.find()) {
            result.put(kv.group(1).toLowerCase(), kv.group(2).trim());
        }
        return result;
    }

    private String resolveTitle(String filename, Map<String, String> frontmatter, String content) {
        if (frontmatter.containsKey("title")) return frontmatter.get("title");
        Matcher m = HEADING_TITLE_PATTERN.matcher(content);
        if (m.find()) return m.group(1);
        return filename.replaceFirst("\\.md$", "");
    }

    private String extractRootCause(String content) {
        Matcher m = ROOT_CAUSE_SECTION.matcher(content);
        if (!m.find()) return "";
        String section = m.group(1).trim();
        return section.length() > 1500 ? section.substring(0, 1500) : section;
    }

    private List<String> extractClassHints(String content) {
        Set<String> hints = new LinkedHashSet<>();
        Matcher m = CLASS_REFERENCE.matcher(content);
        while (m.find()) hints.add(m.group(1));
        return new ArrayList<>(hints);
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String token : trimmed.split(",")) {
            String stripped = token.trim().replaceAll("^[\"']|[\"']$", "");
            if (!stripped.isEmpty()) result.add(stripped);
        }
        return result;
    }

    private void addChunk(Path file, String content, IngestionContext context) {
        String relativePath = context.projectPath().relativize(file).toString();
        context.documents().add(
                documentFactory.buildDocChunk(context.projectId(), relativePath,
                        ChunkType.README, file.getFileName().toString(), content));
    }
}
