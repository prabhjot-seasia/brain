package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.C4WorkspaceNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class C4StructurizrParser implements ArtifactParser {

    private static final List<String> CANDIDATE_PATHS = List.of(
            "workspace.dsl",
            "docs/workspace.dsl",
            "docs/architecture/workspace.dsl",
            "architecture/workspace.dsl");

    private static final Pattern WORKSPACE_NAME = Pattern.compile(
            "(?im)^\\s*workspace\\s+\"([^\"]+)\"");
    private static final Pattern PERSON_DECL = Pattern.compile(
            "(?im)\\b(\\w+)\\s*=\\s*person\\s+\"([^\"]+)\"");
    private static final Pattern SOFTWARE_SYSTEM_DECL = Pattern.compile(
            "(?im)\\b(\\w+)\\s*=\\s*softwareSystem\\s+\"([^\"]+)\"");
    private static final Pattern CONTAINER_DECL = Pattern.compile(
            "(?im)\\b(\\w+)\\s*=\\s*container\\s+\"([^\"]+)\"");
    private static final Pattern COMPONENT_DECL = Pattern.compile(
            "(?im)\\b(\\w+)\\s*=\\s*component\\s+\"([^\"]+)\"");

    private static final int MAX_DSL_CHARS = 1_000_000;

    @Override
    public String name() {
        return "C4StructurizrParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path file = resolve(context.projectPath());
        if (file == null) return ParseResult.empty();

        C4WorkspaceNode node = new C4WorkspaceNode();
        try {
            String content = new String(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES), StandardCharsets.UTF_8);
            if (content.length() > MAX_DSL_CHARS) {
                content = content.substring(0, MAX_DSL_CHARS);
            }
            String relativePath = context.projectPath().relativize(file).toString();

            node.setId(context.projectId() + ":c4:" + relativePath);
            node.setProjectId(context.projectId());
            node.setSourceFile(relativePath);

            Matcher workspace = WORKSPACE_NAME.matcher(content);
            node.setWorkspaceName(workspace.find() ? workspace.group(1) : "Workspace");

            node.setPersons(extractList(content, PERSON_DECL));
            node.setSystems(extractList(content, SOFTWARE_SYSTEM_DECL));
            node.setContainers(extractList(content, CONTAINER_DECL));
            node.setComponents(extractList(content, COMPONENT_DECL));
        } catch (IOException | RuntimeException e) {
            log.warn("C4StructurizrParser failed at {}: {}", file, e.getMessage());
            return ParseResult.empty();
        }

        context.projectNode().setC4Workspace(node);
        log.info("C4StructurizrParser ingested workspace='{}' for project={}: {} systems, {} containers, {} components",
                node.getWorkspaceName(), context.projectId(),
                node.getSystems().size(), node.getContainers().size(), node.getComponents().size());
        return ParseResult.of(Map.of(
                "workspaceName", node.getWorkspaceName(),
                "systems", node.getSystems().size(),
                "containers", node.getContainers().size(),
                "components", node.getComponents().size()));
    }

    private List<String> extractList(String content, Pattern pattern) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = pattern.matcher(content);
        while (m.find()) names.add(m.group(2));
        return new ArrayList<>(names);
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
