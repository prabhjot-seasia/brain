package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.TestCaseNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Log4j2
@Component
@RequiredArgsConstructor
public class ZephyrTcmsParser implements ArtifactParser {

    private static final List<String> TCMS_PATHS = List.of(
            "tcms/zephyr-export.json",
            "tcms/xray-export.json",
            "tcms/qtest-export.json",
            "docs/tcms/export.json");

    private static final int MAX_TEST_CASES = 1000;
    private static final int MAX_FILE_BYTES = 5_000_000;

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "ZephyrTcmsParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveAll(context.projectPath()).findAny().isPresent();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        ProjectNode projectNode = context.projectNode();
        int filesParsed = 0;
        int casesAdded = 0;

        try (Stream<Path> stream = resolveAll(context.projectPath())) {
            for (Path file : stream.toList()) {
                try {
                    byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(file, MAX_FILE_BYTES);
                    JsonNode root = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                    JsonNode cases = root.has("testCases") ? root.get("testCases") : root;
                    if (!cases.isArray()) continue;
                    String source = inferSource(file.getFileName().toString());
                    for (JsonNode entry : cases) {
                        if (casesAdded >= MAX_TEST_CASES) break;
                        TestCaseNode tc = parseEntry(entry, context.projectId(), source);
                        if (tc != null) {
                            projectNode.getTestCases().add(tc);
                            casesAdded++;
                        }
                    }
                    filesParsed++;
                } catch (IOException | RuntimeException e) {
                    log.debug("ZephyrTcmsParser skipped {}: {}", file, e.getMessage());
                }
            }
        }

        return ParseResult.of(Map.of(
                "tcmsFiles", filesParsed,
                "tcmsTestCases", casesAdded));
    }

    private TestCaseNode parseEntry(JsonNode entry, String projectId, String source) {
        String externalId = textOrNull(entry, "key", "id", "externalId");
        if (externalId == null || externalId.isBlank()) return null;
        TestCaseNode tc = new TestCaseNode();
        tc.setId(projectId + ":" + source + ":" + externalId);
        tc.setProjectId(projectId);
        tc.setSource(source);
        tc.setExternalId(externalId);
        tc.setTitle(textOrNull(entry, "title", "summary", "name"));
        tc.setPriority(textOrNull(entry, "priority"));
        tc.setStatus(textOrNull(entry, "status"));
        tc.setGherkin(textOrNull(entry, "gherkin", "steps"));
        tc.setLastExecuted(textOrNull(entry, "lastExecuted", "lastRun"));
        return tc;
    }

    private String inferSource(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.contains("zephyr")) return "ZEPHYR";
        if (lower.contains("xray")) return "XRAY";
        if (lower.contains("qtest")) return "QTEST";
        return "TCMS";
    }

    private String textOrNull(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key) && node.get(key).isTextual()) return node.get(key).asText();
        }
        return null;
    }

    private Stream<Path> resolveAll(Path projectPath) {
        return TCMS_PATHS.stream()
                .map(projectPath::resolve)
                .filter(p -> Files.exists(p) && Files.isRegularFile(p));
    }
}
