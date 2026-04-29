package com.assurant.brain.ingest.parsers;

import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.graph.node.RunbookNode;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
@RequiredArgsConstructor
public class RunbookParser implements ArtifactParser {

    private static final List<String> RUNBOOK_DIRECTORIES = List.of(
            "runbooks",
            "docs/runbooks",
            "docs/runbook",
            "operations/runbooks");

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "^(\\w[\\w-]*)\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern HEADING_TITLE_PATTERN = Pattern.compile(
            "^#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private final IngestDocumentFactory documentFactory;

    @Override
    public String name() {
        return "RunbookParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return RUNBOOK_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        List<RunbookNode> runbooks = new ArrayList<>();
        int chunksAdded = 0;

        for (String dir : RUNBOOK_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            chunksAdded += parseDirectory(root, context, runbooks);
        }

        if (!runbooks.isEmpty()) {
            context.projectNode().getRunbooks().addAll(runbooks);
            log.info("RunbookParser ingested {} runbooks for project={}",
                    runbooks.size(), context.projectId());
        }
        return ParseResult.of(Map.of(
                "runbooks", runbooks.size(),
                "chunks", chunksAdded));
    }

    private int parseDirectory(Path dir, IngestionContext context, List<RunbookNode> runbooks) {
        int chunksAdded = 0;
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), dir)) {
            List<Path> markdownFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : markdownFiles) {
                try {
                    String content = Files.readString(file);
                    RunbookNode runbook = buildRunbook(file, content, context);
                    runbooks.add(runbook);
                    addChunk(file, content, context);
                    chunksAdded++;
                } catch (IOException e) {
                    log.warn("Failed to read runbook file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk runbook directory {}: {}", dir, e.getMessage());
        }
        return chunksAdded;
    }

    private RunbookNode buildRunbook(Path file, String content, IngestionContext context) {
        Map<String, String> frontmatter = extractFrontmatter(content);
        String filename = file.getFileName().toString();
        String relativePath = context.projectPath().relativize(file).toString();

        RunbookNode runbook = new RunbookNode();
        runbook.setId(context.projectId() + ":runbook:" + relativePath);
        runbook.setProjectId(context.projectId());
        runbook.setTitle(resolveTitle(filename, frontmatter, content));
        runbook.setFilePath(relativePath);
        runbook.setTriggers(parseList(frontmatter.get("triggers")));
        runbook.setTargetServices(parseList(frontmatter.get("services")));
        return runbook;
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
