package com.assurant.brain.ingest.parsers;

import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.graph.node.DecisionNode;
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
public class AdrParser implements ArtifactParser {

    private static final List<String> ADR_DIRECTORIES = List.of(
            "docs/adr",
            "docs/architecture/decisions",
            "adr",
            "decisions"
    );

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "^(\\w[\\w-]*)\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern HEADING_TITLE_PATTERN = Pattern.compile(
            "^#\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private final IngestDocumentFactory documentFactory;

    @Override
    public String name() {
        return "AdrParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return ADR_DIRECTORIES.stream().anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        List<DecisionNode> decisions = new ArrayList<>();
        int chunksAdded = 0;

        for (String adrDir : ADR_DIRECTORIES) {
            Path dir = context.projectPath().resolve(adrDir);
            if (!Files.isDirectory(dir)) continue;
            chunksAdded += parseDirectory(dir, context, decisions);
        }

        if (!decisions.isEmpty()) {
            context.projectNode().getDecisions().addAll(decisions);
            log.info("AdrParser ingested {} decisions for project={}", decisions.size(), context.projectId());
        }

        return ParseResult.of(Map.of(
                "decisions", decisions.size(),
                "chunks", chunksAdded));
    }

    private int parseDirectory(Path dir, IngestionContext context, List<DecisionNode> decisions) {
        int chunksAdded = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> markdownFiles = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .toList();
            for (Path file : markdownFiles) {
                try {
                    String content = Files.readString(file);
                    DecisionNode decision = buildDecision(file, content, context);
                    decisions.add(decision);
                    addChunk(file, content, context);
                    chunksAdded++;
                } catch (IOException e) {
                    log.warn("Failed to read ADR file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk ADR directory {}: {}", dir, e.getMessage());
        }
        return chunksAdded;
    }

    private DecisionNode buildDecision(Path file, String content, IngestionContext context) {
        Map<String, String> frontmatter = extractFrontmatter(content);
        String filename = file.getFileName().toString();
        String relativePath = context.projectPath().relativize(file).toString();

        DecisionNode decision = new DecisionNode();
        decision.setId(context.projectId() + ":" + relativePath);
        decision.setProjectId(context.projectId());
        decision.setSource("ADR");
        decision.setFilePath(relativePath);
        decision.setTitle(resolveTitle(filename, frontmatter, content));
        decision.setStatus(frontmatter.getOrDefault("status", "PROPOSED").toUpperCase());
        decision.setDate(frontmatter.getOrDefault("date", ""));
        decision.setSupersedes(frontmatter.get("supersedes"));
        return decision;
    }

    private Map<String, String> extractFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) return result;
        String block = m.group(1);
        Matcher kv = KEY_VALUE_PATTERN.matcher(block);
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

    private void addChunk(Path file, String content, IngestionContext context) {
        String relativePath = context.projectPath().relativize(file).toString();
        context.documents().add(
                documentFactory.buildDocChunk(context.projectId(), relativePath,
                        ChunkType.ADR, file.getFileName().toString(), content));
    }
}
