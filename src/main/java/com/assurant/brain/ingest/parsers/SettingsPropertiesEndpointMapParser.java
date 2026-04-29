package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ApiEndpointMapNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class SettingsPropertiesEndpointMapParser implements ArtifactParser {

    private static final List<String> CANDIDATE_FILES = List.of(
            "settings.properties",
            "src/main/resources/settings.properties",
            "src/main/resources/api-settings.properties",
            "src/main/resources/endpoints.properties");

    private static final Pattern API_PATH_VALUE = Pattern.compile(
            "(?i)^/?(api/|v\\d+/|/v\\d+/|rest/|services/)[^\\s]*$");

    private static final Pattern VERSION_TOKEN = Pattern.compile("(?i)/(v\\d+)(?:/|$)");

    private static final int MAX_ALIASES_PER_FILE = 500;

    @Override
    public String name() {
        return "SettingsPropertiesEndpointMapParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveSettingsFile(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path settings = resolveSettingsFile(context.projectPath());
        if (settings == null) return ParseResult.empty();

        String relativePath = context.projectPath().relativize(settings).toString();
        Map<String, ApiEndpointMapNode> nodesById = new LinkedHashMap<>();
        try {
            byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(settings, IngestPathFilter.MAX_PARSE_BYTES);
            Properties props = new Properties();
            props.load(new StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            int kept = 0;
            for (Map.Entry<Object, Object> entry : props.entrySet()) {
                if (kept >= MAX_ALIASES_PER_FILE) {
                    log.debug("SettingsPropertiesEndpointMapParser truncated {} at {} aliases",
                            relativePath, MAX_ALIASES_PER_FILE);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                if (!API_PATH_VALUE.matcher(value).matches()) continue;

                ApiEndpointMapNode node = new ApiEndpointMapNode();
                node.setId(context.projectId() + ":apiMap:" + key);
                node.setProjectId(context.projectId());
                node.setAlias(key);
                node.setRelativePath(value);
                node.setVersionRange(extractVersionToken(value));
                node.setSourceProperty(relativePath);
                nodesById.put(node.getId(), node);
                kept++;
            }
        } catch (IOException e) {
            log.warn("SettingsPropertiesEndpointMapParser failed to read {}: {}", settings, e.getMessage());
            return ParseResult.empty();
        }

        attachEndpoints(context.projectNode(), nodesById.values());
        log.info("SettingsPropertiesEndpointMapParser ingested {} endpoint aliases for project={}",
                nodesById.size(), context.projectId());
        return ParseResult.of(Map.of("aliases", nodesById.size()));
    }

    private Path resolveSettingsFile(Path projectPath) {
        for (String relative : CANDIDATE_FILES) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return walkForSettingsProperties(projectPath);
    }

    private Path walkForSettingsProperties(Path projectPath) {
        try (Stream<Path> stream = Files.walk(projectPath, 5)) {
            return stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("settings.properties"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String extractVersionToken(String value) {
        Matcher m = VERSION_TOKEN.matcher(value);
        return m.find() ? m.group(1) : "";
    }

    private void attachEndpoints(ProjectNode projectNode, Iterable<ApiEndpointMapNode> nodes) {
        Set<String> existing = new HashSet<>();
        projectNode.getApiEndpointMap().forEach(n -> existing.add(n.getId()));
        for (ApiEndpointMapNode node : nodes) {
            if (existing.add(node.getId())) {
                projectNode.getApiEndpointMap().add(node);
            }
        }
    }
}
