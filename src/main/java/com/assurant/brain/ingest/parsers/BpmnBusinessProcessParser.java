package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.BusinessProcessNode;
import com.assurant.brain.graph.node.ProjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
public class BpmnBusinessProcessParser implements ArtifactParser {

    private static final List<String> SCAN_DIRS = List.of("bpmn", "docs/bpmn", "processes");

    private static final Pattern BPMN_PROCESS = Pattern.compile(
            "(?is)<(?:bpmn:|bpmn2:)?process\\s+([^>]*)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:]+)\\s*=\\s*\"([^\"]*)\"");

    private static final int MAX_PROCESSES = 200;
    private static final int MAX_FILE_BYTES = 2_000_000;

    @Override
    public String name() {
        return "BpmnBusinessProcessParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        for (String dir : SCAN_DIRS) {
            Path candidate = context.projectPath().resolve(dir);
            if (Files.isDirectory(candidate)) return true;
        }
        return false;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        ProjectNode projectNode = context.projectNode();
        int filesParsed = 0;
        int processesAdded = 0;

        for (String dir : SCAN_DIRS) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;

            try (var stream = IngestPathFilter.safeWalk(context.projectPath(), root)) {
                List<Path> bpmnFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".bpmn")
                                || p.getFileName().toString().toLowerCase().endsWith(".bpmn20.xml"))
                        .toList();
                for (Path file : bpmnFiles) {
                    if (processesAdded >= MAX_PROCESSES) break;
                    try {
                        byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(file, MAX_FILE_BYTES);
                        String content = new String(bytes, StandardCharsets.UTF_8);
                        String relativePath = context.projectPath().relativize(file).toString();
                        processesAdded += extractProcesses(content, relativePath, context, projectNode);
                        filesParsed++;
                    } catch (IOException | RuntimeException e) {
                        log.debug("BpmnBusinessProcessParser skipped {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("BpmnBusinessProcessParser walk failed for {}: {}", root, e.getMessage());
            }
        }

        return ParseResult.of(Map.of(
                "bpmnFiles", filesParsed,
                "businessProcesses", processesAdded));
    }

    private int extractProcesses(String content, String relativePath, IngestionContext context,
                                  ProjectNode projectNode) {
        Matcher m = BPMN_PROCESS.matcher(content);
        int added = 0;
        while (m.find()) {
            Map<String, String> attrs = parseAttributes(m.group(1));
            String id = attrs.getOrDefault("id", "");
            if (id.isBlank()) continue;
            BusinessProcessNode node = new BusinessProcessNode();
            node.setId(context.projectId() + ":bpmn:" + id);
            node.setName(attrs.getOrDefault("name", id));
            node.setProcessType("BPMN");
            node.setBpmnUrl(relativePath);
            projectNode.getBusinessProcesses().add(node);
            added++;
        }
        return added;
    }

    private Map<String, String> parseAttributes(String fragment) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher attr = ATTRIBUTE.matcher(fragment);
        while (attr.find()) result.put(attr.group(1), attr.group(2));
        return result;
    }
}
