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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class OpenApiContractParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<String> CANDIDATE_FILENAMES = List.of(
            "openapi.yaml", "openapi.yml", "openapi.json",
            "swagger.yaml", "swagger.yml", "swagger.json");

    private static final List<String> CANDIDATE_DIRECTORIES = List.of(
            "src/main/resources", "src/main/resources/api", "api", "docs", "spec",
            "src/main/resources/openapi");

    private static final Pattern HTTP_METHODS = Pattern.compile(
            "(?i)^(get|post|put|patch|delete|options|head)$");

    @Override
    public String name() {
        return "OpenApiContractParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findOpenApiFiles(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ExternalApiContractNode> contractsByPath = new LinkedHashMap<>();
        int endpointsAdded = 0;

        for (Path file : findOpenApiFiles(context.projectPath())) {
            try {
                JsonNode root = readSpec(file);
                if (!isOpenApiSpec(root)) continue;
                ExternalApiContractNode contract = buildContract(file, root, context);
                contractsByPath.put(contract.getId(), contract);
                endpointsAdded += extractEndpoints(root, contract, context.projectId());
            } catch (IOException e) {
                log.debug("OpenApiContractParser skipped {}: {}", file, e.getMessage());
            }
        }

        attachContracts(context.projectNode(), contractsByPath.values());
        log.info("OpenApiContractParser ingested {} OpenAPI specs ({} endpoints) for project={}",
                contractsByPath.size(), endpointsAdded, context.projectId());
        return ParseResult.of(Map.of(
                "specs", contractsByPath.size(),
                "endpoints", endpointsAdded));
    }

    private List<Path> findOpenApiFiles(Path projectPath) {
        java.util.LinkedHashSet<Path> unique = new java.util.LinkedHashSet<>();
        for (String dir : CANDIDATE_DIRECTORIES) {
            Path resolved = projectPath.resolve(dir);
            if (!Files.isDirectory(resolved)) continue;
            scanForCandidates(resolved).forEach(unique::add);
        }
        return new java.util.ArrayList<>(unique);
    }

    private Stream<Path> scanForCandidates(Path dir) {
        try (Stream<Path> stream = Files.walk(dir, 3)) {
            return stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> CANDIDATE_FILENAMES.contains(p.getFileName().toString().toLowerCase()))
                    .toList()
                    .stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private JsonNode readSpec(Path file) throws IOException {
        ObjectMapper mapper = file.getFileName().toString().toLowerCase().endsWith(".json")
                ? JSON_MAPPER : YAML_MAPPER;
        return mapper.readTree(IngestPathFilter.readAllBytesIfWithinLimit(file, IngestPathFilter.MAX_PARSE_BYTES));
    }

    private boolean isOpenApiSpec(JsonNode root) {
        return root.has("openapi") || root.has("swagger");
    }

    private ExternalApiContractNode buildContract(Path file, JsonNode root, IngestionContext context) {
        String relativePath = context.projectPath().relativize(file).toString();
        String title = root.path("info").path("title").asText("");
        String serviceName = StringUtils.defaultIfBlank(
                StringUtils.lowerCase(title).replaceAll("[^a-z0-9-]", "-"),
                file.getParent().getFileName().toString());

        ExternalApiContractNode contract = new ExternalApiContractNode();
        contract.setId(context.projectId() + ":openapi:" + relativePath);
        contract.setFormat("OPENAPI");
        contract.setServiceName(serviceName);
        contract.setPath(relativePath);
        contract.setSpecVersion(root.path("openapi").asText(root.path("swagger").asText("")));
        return contract;
    }

    private int extractEndpoints(JsonNode root, ExternalApiContractNode contract, String projectId) {
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) return 0;
        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIt.next();
            String pathValue = pathEntry.getKey();
            JsonNode operations = pathEntry.getValue();
            Iterator<Map.Entry<String, JsonNode>> opIt = operations.fields();
            while (opIt.hasNext()) {
                Map.Entry<String, JsonNode> opEntry = opIt.next();
                String method = opEntry.getKey();
                if (!HTTP_METHODS.matcher(method).matches()) continue;
                EndpointNode endpoint = new EndpointNode();
                endpoint.setId(contract.getId() + ":" + method.toUpperCase() + ":" + pathValue);
                endpoint.setProjectId(projectId);
                endpoint.setPath(pathValue);
                endpoint.setHttpMethod(method.toUpperCase());
                endpoint.setSource("OPENAPI");
                if (contract.getEndpoints().stream().noneMatch(e -> e.getId().equals(endpoint.getId()))) {
                    contract.getEndpoints().add(endpoint);
                    count++;
                }
            }
        }
        return count;
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
