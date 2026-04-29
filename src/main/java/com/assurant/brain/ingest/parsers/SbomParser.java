package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.SbomComponentNode;
import com.assurant.brain.graph.node.SbomNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class SbomParser implements ArtifactParser {

    private static final List<String> SBOM_LOCATIONS = List.of(
            "bom.json",
            "sbom.json",
            "cyclonedx.json",
            "build/reports/bom.json",
            "build/reports/cyclonedx/bom.json",
            "target/bom.json"
    );

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "SbomParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveSbomPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path sbomPath = resolveSbomPath(context.projectPath());
        if (sbomPath == null) {
            return ParseResult.empty();
        }

        SbomNode sbom;
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(sbomPath, IngestPathFilter.MAX_PARSE_BYTES));
            String format = root.path("bomFormat").asText("CycloneDX");
            if (!"CycloneDX".equalsIgnoreCase(format)) {
                log.info("SbomParser found {} but it is not CycloneDX (bomFormat={}). Skipping.", sbomPath, format);
                return ParseResult.empty();
            }
            sbom = buildSbom(context, sbomPath, root);
        } catch (IOException e) {
            log.warn("Failed to parse SBOM at {}: {}", sbomPath, e.getMessage());
            return ParseResult.empty();
        }

        context.projectNode().getSboms().add(sbom);
        log.info("SbomParser ingested SBOM with {} components for project={}",
                sbom.getComponents().size(), context.projectId());
        return ParseResult.of(Map.of(
                "sbomFormat", sbom.getFormat(),
                "specVersion", StringUtils.defaultString(sbom.getSpecVersion()),
                "componentCount", sbom.getComponents().size()));
    }

    private SbomNode buildSbom(IngestionContext context, Path sbomPath, JsonNode root) {
        String relativePath = context.projectPath().relativize(sbomPath).toString();
        SbomNode sbom = new SbomNode();
        sbom.setId(context.projectId() + ":sbom:" + relativePath);
        sbom.setProjectId(context.projectId());
        sbom.setFormat("CycloneDX");
        sbom.setSpecVersion(root.path("specVersion").asText(""));
        sbom.setSourcePath(relativePath);
        sbom.setCapturedAt(OffsetDateTime.now().toString());

        List<SbomComponentNode> components = new ArrayList<>();
        JsonNode componentsNode = root.path("components");
        if (componentsNode.isArray()) {
            for (JsonNode component : componentsNode) {
                components.add(buildComponent(context.projectId(), sbom.getId(), component));
            }
        }
        sbom.setComponents(components);
        return sbom;
    }

    private SbomComponentNode buildComponent(String projectId, String sbomId, JsonNode component) {
        SbomComponentNode node = new SbomComponentNode();
        String purl = component.path("purl").asText("");
        String name = component.path("name").asText("");
        String version = component.path("version").asText("");

        String idSeed = StringUtils.isNotBlank(purl) ? purl : (name + "@" + version);
        node.setId(sbomId + "#" + idSeed);
        node.setPurl(purl);
        node.setName(name);
        node.setVersion(version);
        node.setComponentType(component.path("type").asText(""));

        List<String> licenses = new ArrayList<>();
        JsonNode licensesNode = component.path("licenses");
        if (licensesNode.isArray()) {
            for (JsonNode entry : licensesNode) {
                String id = entry.path("license").path("id").asText("");
                String licenseName = entry.path("license").path("name").asText("");
                if (StringUtils.isNotBlank(id)) {
                    licenses.add(id);
                } else if (StringUtils.isNotBlank(licenseName)) {
                    licenses.add(licenseName);
                }
            }
        }
        node.setLicenses(licenses);
        node.setDirectDependency(!component.path("scope").asText("").equalsIgnoreCase("optional"));
        return node;
    }

    private Path resolveSbomPath(Path projectPath) {
        for (String relative : SBOM_LOCATIONS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
