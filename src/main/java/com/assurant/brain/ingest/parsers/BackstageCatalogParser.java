package com.assurant.brain.ingest.parsers;

import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.graph.node.TeamNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestDocumentFactory;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
@RequiredArgsConstructor
public class BackstageCatalogParser implements ArtifactParser {

    private static final List<String> CATALOG_LOCATIONS = List.of(
            "catalog-info.yaml",
            "catalog-info.yml",
            ".backstage/catalog-info.yaml"
    );

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final IngestDocumentFactory documentFactory;

    @Override
    public String name() {
        return "BackstageCatalogParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveCatalogPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path catalog = resolveCatalogPath(context.projectPath());
        if (catalog == null) {
            return ParseResult.empty();
        }

        Map<String, Object> stats = new HashMap<>();
        try {
            JsonNode root = YAML_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(catalog, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode metadata = root.path("metadata");
            JsonNode spec = root.path("spec");

            String kind = root.path("kind").asText("Component");
            String name = metadata.path("name").asText("");
            String description = metadata.path("description").asText("");
            String owner = spec.path("owner").asText("");
            String type = spec.path("type").asText("");
            String lifecycle = spec.path("lifecycle").asText("");
            String system = spec.path("system").asText("");

            stats.put("kind", kind);
            stats.put("name", name);
            stats.put("type", type);
            stats.put("lifecycle", lifecycle);

            if (StringUtils.isNotBlank(owner)) {
                String ownerHandle = owner.startsWith("@") ? owner : "@" + owner;
                if (!ownerAlreadyPresent(context, ownerHandle)) {
                    TeamNode team = new TeamNode();
                    team.setId(context.projectId() + ":" + ownerHandle);
                    team.setName(ownerHandle);
                    team.setSource("BACKSTAGE");
                    context.projectNode().getOwners().add(team);
                    stats.put("addedTeamFromBackstage", ownerHandle);
                }
            }

            if (StringUtils.isNotBlank(description)) {
                String relativePath = context.projectPath().relativize(catalog).toString();
                Map<String, Object> extra = new HashMap<>();
                extra.put("backstageKind", kind);
                extra.put("backstageType", type);
                extra.put("backstageLifecycle", lifecycle);
                extra.put("backstageSystem", system);
                context.documents().add(
                        documentFactory.buildDocChunk(context.projectId(), relativePath,
                                ChunkType.README, name, description, extra));
            }

            log.info("BackstageCatalogParser parsed catalog-info kind={} name={} for project={}",
                    kind, name, context.projectId());
        } catch (IOException e) {
            log.warn("Failed to parse Backstage catalog at {}: {}", catalog, e.getMessage());
            return ParseResult.empty();
        }

        return ParseResult.of(stats);
    }

    private Path resolveCatalogPath(Path projectPath) {
        for (String relative : CATALOG_LOCATIONS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean ownerAlreadyPresent(IngestionContext context, String ownerHandle) {
        return context.projectNode().getOwners().stream()
                .anyMatch(t -> ownerHandle.equalsIgnoreCase(t.getName()));
    }
}
