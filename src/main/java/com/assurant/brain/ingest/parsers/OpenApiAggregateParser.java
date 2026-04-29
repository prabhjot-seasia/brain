package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ApiDocumentRegistryNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class OpenApiAggregateParser implements ArtifactParser {

    private static final List<String> MASTER_SPEC_CANDIDATES = List.of(
            "src/main/resources/main-spec.yaml",
            "src/main/resources/main-spec.yml",
            "main-spec.yaml",
            "main-spec.yml");

    private static final List<String> DEFINITION_DIRECTORIES = List.of(
            "src/main/resources/definitions",
            "definitions",
            "src/main/resources/api/definitions");

    @Override
    public String name() {
        return "OpenApiAggregateParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        if (resolveMasterSpec(context.projectPath()) != null) return true;
        return DEFINITION_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path masterSpec = resolveMasterSpec(context.projectPath());
        Path definitionsRoot = resolveDefinitionsRoot(context.projectPath());
        if (masterSpec == null && definitionsRoot == null) return ParseResult.empty();

        ApiDocumentRegistryNode registry = new ApiDocumentRegistryNode();
        registry.setId(context.projectId() + ":apiDocRegistry");
        registry.setProjectId(context.projectId());
        if (masterSpec != null) {
            registry.setMasterSpecPath(context.projectPath().relativize(masterSpec).toString());
        }
        Set<String> domains = collectDomainNames(definitionsRoot);
        registry.getDomains().addAll(domains);

        context.projectNode().setApiDocumentRegistry(registry);
        log.info("OpenApiAggregateParser registered API document with master={} and {} domains for project={}",
                registry.getMasterSpecPath(), registry.getDomains().size(), context.projectId());
        return ParseResult.of(Map.of(
                "masterSpec", StringUtils.defaultString(registry.getMasterSpecPath()),
                "domains", registry.getDomains().size()));
    }

    private Path resolveMasterSpec(Path projectPath) {
        for (String candidate : MASTER_SPEC_CANDIDATES) {
            Path p = projectPath.resolve(candidate);
            if (Files.exists(p) && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private Path resolveDefinitionsRoot(Path projectPath) {
        for (String dir : DEFINITION_DIRECTORIES) {
            Path p = projectPath.resolve(dir);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private Set<String> collectDomainNames(Path definitionsRoot) {
        Set<String> domains = new LinkedHashSet<>();
        if (definitionsRoot == null) return domains;
        try (Stream<Path> stream = Files.list(definitionsRoot)) {
            stream.filter(Files::isDirectory).forEach(p -> domains.add(p.getFileName().toString()));
        } catch (IOException e) {
            log.debug("OpenApiAggregateParser failed to list domains in {}: {}", definitionsRoot, e.getMessage());
        }
        return domains;
    }
}
