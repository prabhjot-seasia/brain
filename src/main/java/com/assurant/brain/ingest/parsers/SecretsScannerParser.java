package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SecurityFindingNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class SecretsScannerParser implements ArtifactParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final List<CandidateSpec> CANDIDATES = List.of(
            new CandidateSpec("ops/security/gitleaks.json", "GITLEAKS"),
            new CandidateSpec("ops/gitleaks.json", "GITLEAKS"),
            new CandidateSpec("gitleaks-report.json", "GITLEAKS"),
            new CandidateSpec("ops/security/trufflehog.json", "TRUFFLEHOG"),
            new CandidateSpec("ops/trufflehog.json", "TRUFFLEHOG"),
            new CandidateSpec("trufflehog-report.json", "TRUFFLEHOG"));

    private static final int MAX_FINDINGS_PER_FILE = 500;

    @Override
    public String name() {
        return "SecretsScannerParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        ResolvedReport report = resolve(context.projectPath());
        if (report == null) return ParseResult.empty();

        Map<String, SecurityFindingNode> findingsById = new LinkedHashMap<>();
        try {
            byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(
                    report.path(), IngestPathFilter.MAX_PARSE_BYTES);
            if ("GITLEAKS".equals(report.scanner())) {
                parseGitleaks(bytes, context, findingsById);
            } else {
                parseTrufflehog(bytes, context, findingsById);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("SecretsScannerParser failed at {}: {}", report.path(), e.getMessage());
            return ParseResult.empty();
        }

        attachFindings(context.projectNode(), findingsById.values());
        log.info("SecretsScannerParser ingested {} findings ({}) for project={}",
                findingsById.size(), report.scanner(), context.projectId());
        return ParseResult.of(Map.of(
                "scanner", report.scanner(),
                "findings", findingsById.size()));
    }

    private void parseGitleaks(byte[] bytes, IngestionContext context,
                                Map<String, SecurityFindingNode> findings) throws IOException {
        JsonNode root = JSON_MAPPER.readTree(bytes);
        JsonNode array = root.isArray() ? root : root.path("findings");
        if (!array.isArray()) return;
        for (JsonNode finding : array) {
            if (findings.size() >= MAX_FINDINGS_PER_FILE) break;
            String ruleId = finding.path("RuleID").asText(finding.path("ruleId").asText(""));
            String description = finding.path("Description").asText(
                    finding.path("description").asText(""));
            String file = finding.path("File").asText(finding.path("file").asText(""));
            int startLine = finding.path("StartLine").asInt(finding.path("startLine").asInt(0));
            String commit = finding.path("Commit").asText(finding.path("commit").asText(""));
            String locator = file + ":" + startLine;

            SecurityFindingNode node = new SecurityFindingNode();
            node.setId(context.projectId() + ":secFinding:GITLEAKS:" + locator + ":" + ruleId);
            node.setProjectId(context.projectId());
            node.setScanner("GITLEAKS");
            node.setSeverity("HIGH");
            node.setRuleId(ruleId);
            node.setDescription(description);
            node.setLocator(locator);
            node.setCommit(commit);
            node.setCapturedAt(OffsetDateTime.now().toString());
            findings.putIfAbsent(node.getId(), node);
        }
    }

    private void parseTrufflehog(byte[] bytes, IngestionContext context,
                                  Map<String, SecurityFindingNode> findings) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<String> jsonLines = new ArrayList<>();
        if (content.trim().startsWith("[")) {
            try {
                JsonNode array = JSON_MAPPER.readTree(content);
                if (array.isArray()) {
                    for (JsonNode entry : array) jsonLines.add(entry.toString());
                }
            } catch (IOException e) {
                log.debug("SecretsScannerParser failed to parse Trufflehog array: {}", e.getMessage());
            }
        } else {
            for (String line : content.split("\\R")) {
                if (StringUtils.isNotBlank(line) && line.trim().startsWith("{")) {
                    jsonLines.add(line);
                }
            }
        }

        for (String line : jsonLines) {
            if (findings.size() >= MAX_FINDINGS_PER_FILE) break;
            try {
                JsonNode entry = JSON_MAPPER.readTree(line);
                String detector = entry.path("DetectorName").asText(
                        entry.path("detector").asText("unknown"));
                String reason = entry.path("Reason").asText(entry.path("reason").asText(""));
                JsonNode source = entry.path("SourceMetadata").path("Data").path("Filesystem");
                String file = source.path("file").asText(entry.path("file").asText(""));
                int line2 = source.path("line").asInt(entry.path("line").asInt(0));
                String locator = file + ":" + line2;

                SecurityFindingNode node = new SecurityFindingNode();
                node.setId(context.projectId() + ":secFinding:TRUFFLEHOG:" + locator + ":" + detector);
                node.setProjectId(context.projectId());
                node.setScanner("TRUFFLEHOG");
                node.setSeverity(entry.path("Verified").asBoolean(false) ? "CRITICAL" : "HIGH");
                node.setRuleId(detector);
                node.setDescription(StringUtils.defaultIfBlank(reason, "Detected secret of type " + detector));
                node.setLocator(locator);
                node.setCommit(entry.path("commit").asText(""));
                node.setCapturedAt(OffsetDateTime.now().toString());
                findings.putIfAbsent(node.getId(), node);
            } catch (IOException e) {
                log.debug("SecretsScannerParser failed to parse Trufflehog line: {}", e.getMessage());
            }
        }
    }

    private ResolvedReport resolve(Path projectPath) {
        for (CandidateSpec spec : CANDIDATES) {
            Path candidate = projectPath.resolve(spec.relativePath());
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return new ResolvedReport(candidate, spec.scanner());
            }
        }
        return null;
    }

    private void attachFindings(ProjectNode projectNode, Iterable<SecurityFindingNode> findings) {
        Set<String> existing = new HashSet<>();
        projectNode.getSecurityFindings().forEach(f -> existing.add(f.getId()));
        for (SecurityFindingNode finding : findings) {
            if (existing.add(finding.getId())) {
                projectNode.getSecurityFindings().add(finding);
            }
        }
    }

    private record CandidateSpec(String relativePath, String scanner) {}

    private record ResolvedReport(Path path, String scanner) {}
}
