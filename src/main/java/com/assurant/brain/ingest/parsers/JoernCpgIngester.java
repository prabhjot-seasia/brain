package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.CodePropertyGraphNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class JoernCpgIngester implements ArtifactParser {

    private static final List<String> CPG_DIRECTORIES = List.of(
            "ops/cpg",
            "ops/security/cpg",
            "docs/ops/cpg");

    private static final int MAX_CPG_ARTIFACTS = 500;

    @Override
    public String name() {
        return "JoernCpgIngester";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return CPG_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, CodePropertyGraphNode> nodesById = new LinkedHashMap<>();

        for (String dir : CPG_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(root, context, nodesById);
        }

        attachNodes(context.projectNode(), nodesById.values());
        log.info("JoernCpgIngester registered {} CPG artifacts for project={}",
                nodesById.size(), context.projectId());
        return ParseResult.of(Map.of("cpgArtifacts", nodesById.size()));
    }

    private void walk(Path root, IngestionContext context, Map<String, CodePropertyGraphNode> nodesById) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), root)) {
            List<Path> artifacts = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".cpg.bin")
                                || name.endsWith(".atom")
                                || name.endsWith(".cpg.json");
                    })
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : artifacts) {
                if (nodesById.size() >= MAX_CPG_ARTIFACTS) break;
                buildNode(file, context, nodesById);
            }
        } catch (IOException e) {
            log.warn("JoernCpgIngester failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void buildNode(Path file, IngestionContext context, Map<String, CodePropertyGraphNode> nodesById) {
        String filename = file.getFileName().toString();
        String relativePath = context.projectPath().relativize(file).toString();
        CodePropertyGraphNode node = new CodePropertyGraphNode();
        node.setId(context.projectId() + ":cpg:" + relativePath);
        node.setProjectId(context.projectId());
        node.setFormat(detectFormat(filename));
        node.setArtifactPath(relativePath);
        node.setClassFqnHint(inferClassFqn(filename));
        node.setCapturedAt(OffsetDateTime.now().toString());
        nodesById.putIfAbsent(node.getId(), node);
    }

    private String detectFormat(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".cpg.bin")) return "JOERN";
        if (lower.endsWith(".atom")) return "ATOM";
        if (lower.endsWith(".cpg.json")) return "JOERN_JSON";
        return "UNKNOWN";
    }

    private String inferClassFqn(String filename) {
        return filename.replaceFirst("\\.(?:cpg\\.bin|atom|cpg\\.json)$", "");
    }

    private void attachNodes(ProjectNode projectNode, Iterable<CodePropertyGraphNode> nodes) {
        Set<String> existing = new HashSet<>();
        projectNode.getCodePropertyGraphs().forEach(c -> existing.add(c.getId()));
        for (CodePropertyGraphNode node : nodes) {
            if (existing.add(node.getId())) {
                projectNode.getCodePropertyGraphs().add(node);
            }
        }
    }
}
