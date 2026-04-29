package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.EndpointNode;
import com.assurant.brain.graph.node.ExternalApiContractNode;
import com.assurant.brain.graph.node.ProjectNode;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class WireMockContractParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> WIREMOCK_DIRECTORIES = List.of(
            "src/test/resources/wiremock",
            "src/test/resources/__files",
            "src/test/resources/mappings",
            "wiremock");

    @Override
    public String name() {
        return "WireMockContractParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return WIREMOCK_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ExternalApiContractNode> contractsByService = new LinkedHashMap<>();

        for (String dir : WIREMOCK_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walkAndCollect(root, context, contractsByService);
        }

        attachContracts(context.projectNode(), contractsByService.values());
        log.info("WireMockContractParser ingested {} contracts for project={}",
                contractsByService.size(), context.projectId());
        return ParseResult.of(Map.of(
                "contracts", contractsByService.size(),
                "endpoints", contractsByService.values().stream()
                        .mapToInt(c -> c.getEndpoints().size()).sum()));
    }

    private void walkAndCollect(Path root, IngestionContext context,
                                 Map<String, ExternalApiContractNode> contractsByService) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> jsons = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .toList();
            for (Path file : jsons) {
                processFile(file, root, context, contractsByService);
            }
        } catch (IOException e) {
            log.warn("WireMockContractParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, Path root, IngestionContext context,
                              Map<String, ExternalApiContractNode> contracts) {
        try {
            JsonNode node = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode request = node.path("request");
            if (request.isMissingNode()) return;
            String method = request.path("method").asText("");
            String path = StringUtils.firstNonBlank(
                    request.path("urlPath").asText(null),
                    request.path("urlPattern").asText(null),
                    request.path("url").asText(null),
                    request.path("urlPathPattern").asText(""));
            if (StringUtils.isBlank(path)) return;

            String serviceName = inferServiceFromRelativePath(root, file);
            ExternalApiContractNode contract = contracts.computeIfAbsent(
                    serviceContractId(context.projectId(), serviceName),
                    k -> newContract(context.projectId(), serviceName, root, file));

            EndpointNode endpoint = new EndpointNode();
            endpoint.setId(contract.getId() + ":" + method + ":" + path);
            endpoint.setProjectId(context.projectId());
            endpoint.setPath(path);
            endpoint.setHttpMethod(method);
            endpoint.setSource("WIREMOCK");
            if (contract.getEndpoints().stream().noneMatch(e -> e.getId().equals(endpoint.getId()))) {
                contract.getEndpoints().add(endpoint);
            }
        } catch (IOException e) {
            log.debug("WireMockContractParser skipped {}: {}", file, e.getMessage());
        }
    }

    private String inferServiceFromRelativePath(Path root, Path file) {
        Path rel = root.relativize(file);
        if (rel.getNameCount() > 1) {
            return rel.getName(0).toString();
        }
        return "default";
    }

    private String serviceContractId(String projectId, String serviceName) {
        return projectId + ":wiremock:" + serviceName;
    }

    private ExternalApiContractNode newContract(String projectId, String serviceName, Path root, Path file) {
        ExternalApiContractNode contract = new ExternalApiContractNode();
        contract.setId(serviceContractId(projectId, serviceName));
        contract.setFormat("WIREMOCK");
        contract.setServiceName(serviceName);
        contract.setPath(root.relativize(file.getParent()).toString());
        return contract;
    }

    private void attachContracts(ProjectNode projectNode, Iterable<ExternalApiContractNode> contracts) {
        Set<String> existing = new HashSet<>();
        projectNode.getContracts().forEach(c -> existing.add(c.getId()));
        for (ExternalApiContractNode contract : contracts) {
            if (existing.add(contract.getId())) {
                projectNode.getContracts().add(contract);
            }
        }
    }
}
