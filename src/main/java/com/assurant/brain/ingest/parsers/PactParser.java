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
public class PactParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> PACT_DIRECTORIES = List.of(
            "pacts",
            "src/test/resources/pacts",
            "build/pacts",
            "target/pacts");

    @Override
    public String name() {
        return "PactParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return PACT_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ExternalApiContractNode> contractsByProvider = new LinkedHashMap<>();

        for (String dir : PACT_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(root, context, contractsByProvider);
        }

        attachContracts(context.projectNode(), contractsByProvider.values());
        log.info("PactParser ingested {} Pact contracts for project={}",
                contractsByProvider.size(), context.projectId());
        return ParseResult.of(Map.of(
                "pactContracts", contractsByProvider.size(),
                "endpoints", contractsByProvider.values().stream()
                        .mapToInt(c -> c.getEndpoints().size()).sum()));
    }

    private void walk(Path root, IngestionContext context,
                      Map<String, ExternalApiContractNode> contracts) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), root)) {
            List<Path> jsons = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : jsons) {
                processFile(file, context, contracts);
            }
        } catch (IOException e) {
            log.warn("PactParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, IngestionContext context,
                              Map<String, ExternalApiContractNode> contracts) {
        try {
            JsonNode root = JSON_MAPPER.readTree(IngestPathFilter.readAllBytesIfWithinLimit(
                    file, IngestPathFilter.MAX_PARSE_BYTES));
            JsonNode interactions = root.path("interactions");
            if (!interactions.isArray()) return;
            String provider = root.path("provider").path("name").asText("");
            String consumer = root.path("consumer").path("name").asText("");
            if (StringUtils.isBlank(provider)) return;

            String contractId = context.projectId() + ":pact:" + provider;
            ExternalApiContractNode contract = contracts.computeIfAbsent(contractId, k -> {
                ExternalApiContractNode c = new ExternalApiContractNode();
                c.setId(contractId);
                c.setFormat("PACT");
                c.setServiceName(provider);
                c.setPath(context.projectPath().relativize(file).toString());
                c.setSpecVersion("CONSUMER:" + consumer);
                return c;
            });

            for (JsonNode interaction : interactions) {
                JsonNode request = interaction.path("request");
                if (request.isMissingNode()) continue;
                String method = request.path("method").asText("").toUpperCase();
                String path = request.path("path").asText("");
                if (StringUtils.isBlank(path)) continue;

                EndpointNode endpoint = new EndpointNode();
                endpoint.setId(contract.getId() + ":" + method + ":" + path);
                endpoint.setProjectId(context.projectId());
                endpoint.setPath(path);
                endpoint.setHttpMethod(method);
                endpoint.setSource("PACT");
                if (contract.getEndpoints().stream().noneMatch(e -> e.getId().equals(endpoint.getId()))) {
                    contract.getEndpoints().add(endpoint);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("PactParser skipped {}: {}", file, e.getMessage());
        }
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
