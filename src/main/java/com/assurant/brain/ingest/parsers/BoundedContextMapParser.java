package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.BoundedContextNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class BoundedContextMapParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final List<String> YAML_CANDIDATES = List.of(
            "context-map.yaml",
            "context-map.yml",
            "docs/context-map.yaml",
            "docs/context-map.yml",
            "domain/context-map.yaml");

    private static final List<String> CML_CANDIDATES = List.of(
            "context-map.cml",
            "docs/context-map.cml",
            "domain/context-map.cml");

    private static final Pattern CML_BOUNDED_CONTEXT = Pattern.compile(
            "(?im)^\\s*BoundedContext\\s+(\\w+)\\b");
    private static final Pattern CML_OWNED_BY = Pattern.compile(
            "(?im)\\bownedBy\\s*=\\s*(\\w+)");

    private static final int MAX_INCLUDED_PROJECTS = 1000;
    private static final int MAX_UBIQUITOUS_TERMS = 1000;

    @Override
    public String name() {
        return "BoundedContextMapParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveYamlPath(context.projectPath()) != null
                || resolveCmlPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path yamlPath = resolveYamlPath(context.projectPath());
        if (yamlPath != null) {
            return parseYaml(yamlPath, context);
        }
        Path cmlPath = resolveCmlPath(context.projectPath());
        if (cmlPath != null) {
            return parseCml(cmlPath, context);
        }
        return ParseResult.empty();
    }

    private ParseResult parseYaml(Path yamlPath, IngestionContext context) {
        try {
            JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    yamlPath, IngestPathFilter.MAX_PARSE_BYTES));
            String name = root.path("name").asText("");
            if (StringUtils.isBlank(name)) {
                name = root.path("metadata").path("name").asText(context.projectId());
            }
            BoundedContextNode boundedContext = new BoundedContextNode();
            boundedContext.setId(context.projectId() + ":context:" + name.toLowerCase());
            boundedContext.setName(name);
            boundedContext.setDisplayName(root.path("displayName").asText(name));
            boundedContext.setOwnerTeamId(StringUtils.firstNonBlank(
                    root.path("owner").asText(null),
                    root.path("ownerTeam").asText(null),
                    ""));
            boundedContext.setSource("YAML");

            JsonNode projects = root.path("projects").isArray()
                    ? root.path("projects")
                    : root.path("includedProjects");
            if (projects.isArray()) {
                List<String> included = new ArrayList<>();
                projects.forEach(p -> {
                    if (included.size() < MAX_INCLUDED_PROJECTS) included.add(p.asText(""));
                });
                boundedContext.setIncludedProjects(included.stream().filter(s -> !s.isBlank()).toList());
            }

            JsonNode terms = root.path("ubiquitousLanguage");
            if (terms.isArray()) {
                List<String> ubiquitous = new ArrayList<>();
                terms.forEach(t -> {
                    if (ubiquitous.size() < MAX_UBIQUITOUS_TERMS) ubiquitous.add(t.asText(""));
                });
                boundedContext.setUbiquitousLanguageTerms(ubiquitous.stream().filter(s -> !s.isBlank()).toList());
            }

            context.projectNode().setBoundedContext(boundedContext);
            log.info("BoundedContextMapParser parsed YAML context-map for project={}: name={}",
                    context.projectId(), name);
            return ParseResult.of(Map.of(
                    "name", name,
                    "source", "YAML",
                    "ownedBy", StringUtils.defaultString(boundedContext.getOwnerTeamId()),
                    "ubiquitousTerms", boundedContext.getUbiquitousLanguageTerms().size(),
                    "includedProjects", boundedContext.getIncludedProjects().size()));
        } catch (IOException | RuntimeException e) {
            log.warn("BoundedContextMapParser failed to read YAML at {}: {}", yamlPath, e.getMessage());
            return ParseResult.empty();
        }
    }

    private ParseResult parseCml(Path cmlPath, IngestionContext context) {
        try {
            String content = new String(IngestPathFilter.readAllBytesIfWithinLimit(
                    cmlPath, IngestPathFilter.MAX_PARSE_BYTES), StandardCharsets.UTF_8);
            Matcher firstContext = CML_BOUNDED_CONTEXT.matcher(content);
            if (!firstContext.find()) {
                log.debug("BoundedContextMapParser found no BoundedContext declaration in {}", cmlPath);
                return ParseResult.empty();
            }
            String name = firstContext.group(1);
            BoundedContextNode boundedContext = new BoundedContextNode();
            boundedContext.setId(context.projectId() + ":context:" + name.toLowerCase());
            boundedContext.setName(name);
            boundedContext.setDisplayName(name);
            boundedContext.setSource("CML");

            Matcher owner = CML_OWNED_BY.matcher(content);
            if (owner.find()) {
                boundedContext.setOwnerTeamId(owner.group(1));
            }
            context.projectNode().setBoundedContext(boundedContext);
            log.info("BoundedContextMapParser parsed CML context-map for project={}: name={}",
                    context.projectId(), name);
            return ParseResult.of(Map.of(
                    "name", name,
                    "source", "CML",
                    "ownedBy", StringUtils.defaultString(boundedContext.getOwnerTeamId())));
        } catch (IOException | RuntimeException e) {
            log.warn("BoundedContextMapParser failed to read CML at {}: {}", cmlPath, e.getMessage());
            return ParseResult.empty();
        }
    }

    private Path resolveYamlPath(Path projectPath) {
        return resolve(projectPath, YAML_CANDIDATES);
    }

    private Path resolveCmlPath(Path projectPath) {
        return resolve(projectPath, CML_CANDIDATES);
    }

    private Path resolve(Path projectPath, List<String> candidates) {
        for (String relative : candidates) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
