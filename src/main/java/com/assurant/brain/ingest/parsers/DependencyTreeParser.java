package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.LibraryNode;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class DependencyTreeParser implements ArtifactParser {

    private static final List<String> CANDIDATE_PATHS = List.of(
            "build/reports/dependencies.txt",
            "build/reports/dependency-tree.txt",
            "ops/dependencies.txt",
            "ops/dependency-tree.txt",
            "target/dependency-tree.txt",
            "dependencies.txt");

    private static final Pattern GAV_LINE = Pattern.compile(
            "(?:[+\\\\|\\-\\s]+)([a-zA-Z0-9_.\\-]+):([a-zA-Z0-9_.\\-]+):([a-zA-Z0-9_.\\-]+)(?:\\s*->\\s*([a-zA-Z0-9_.\\-]+))?");

    private static final int MAX_LIBRARIES_PER_FILE = 1000;
    private static final int MAX_LINE_LENGTH = 4096;

    @Override
    public String name() {
        return "DependencyTreeParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        Map<String, LibraryNode> librariesByName = new LinkedHashMap<>();
        try {
            String content = new String(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES), StandardCharsets.UTF_8);
            for (String line : content.split("\\R")) {
                if (librariesByName.size() >= MAX_LIBRARIES_PER_FILE) break;
                if (line.length() > MAX_LINE_LENGTH) continue;
                Matcher m = GAV_LINE.matcher(line);
                if (!m.find()) continue;
                String groupId = m.group(1);
                String artifactId = m.group(2);
                String declared = m.group(3);
                String resolved = m.group(4);
                if (StringUtils.isAnyBlank(groupId, artifactId, declared)) continue;
                String name = "maven:" + groupId + ":" + artifactId
                        + ":" + StringUtils.defaultIfBlank(resolved, declared);
                LibraryNode node = librariesByName.computeIfAbsent(name, k -> new LibraryNode());
                node.setName(name);
                node.setGroupId(groupId);
                node.setArtifactId(artifactId);
                node.setVersion(StringUtils.defaultIfBlank(resolved, declared));
                node.setPurpose(buildPurpose(declared, resolved));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("DependencyTreeParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        attachLibraries(context.projectNode(), librariesByName.values());
        log.info("DependencyTreeParser ingested {} resolved libraries for project={}",
                librariesByName.size(), context.projectId());
        return ParseResult.of(Map.of("resolvedLibraries", librariesByName.size()));
    }

    private String buildPurpose(String declared, String resolved) {
        if (StringUtils.isNotBlank(resolved) && !resolved.equals(declared)) {
            return "RESOLVED:" + declared + "->" + resolved;
        }
        return "RESOLVED";
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private void attachLibraries(ProjectNode projectNode, Iterable<LibraryNode> libraries) {
        Set<String> existing = new HashSet<>();
        projectNode.getLibraries().forEach(l -> existing.add(l.getName()));
        for (LibraryNode library : libraries) {
            if (existing.add(library.getName())) {
                projectNode.getLibraries().add(library);
            }
        }
    }
}
