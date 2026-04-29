package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecretsScannerParser")
class SecretsScannerParserTest {

    private final SecretsScannerParser parser = new SecretsScannerParser();

    @Test
    @DisplayName("parses gitleaks JSON array into SecurityFindingNode set")
    void parsesGitleaksReport(@TempDir Path projectRoot) throws IOException {
        Path opsSec = projectRoot.resolve("ops/security");
        Files.createDirectories(opsSec);
        Files.writeString(opsSec.resolve("gitleaks.json"), """
                [
                  { "RuleID": "aws-access-key", "Description": "AWS Access Key",
                    "File": "src/main/resources/.env", "StartLine": 12, "Commit": "abc123" },
                  { "RuleID": "github-token", "Description": "GitHub PAT",
                    "File": "scripts/deploy.sh", "StartLine": 45, "Commit": "abc123" }
                ]
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSecurityFindings()).hasSize(2);
        assertThat(projectNode.getSecurityFindings())
                .anyMatch(f -> f.getScanner().equals("GITLEAKS")
                        && f.getRuleId().equals("aws-access-key")
                        && f.getLocator().equals("src/main/resources/.env:12"));
        assertThat(result.stats().get("scanner")).isEqualTo("GITLEAKS");
        assertThat(result.stats().get("findings")).isEqualTo(2);
    }

    @Test
    @DisplayName("parses trufflehog NDJSON output line-by-line")
    void parsesTrufflehogNdjson(@TempDir Path projectRoot) throws IOException {
        Path opsSec = projectRoot.resolve("ops/security");
        Files.createDirectories(opsSec);
        Files.writeString(opsSec.resolve("trufflehog.json"), """
                {"DetectorName":"AWS","Reason":"AWS access key match","Verified":true,"SourceMetadata":{"Data":{"Filesystem":{"file":"src/main/resources/secrets.yaml","line":7}}}}
                {"DetectorName":"Slack","Reason":"Slack webhook","Verified":false,"SourceMetadata":{"Data":{"Filesystem":{"file":"docs/notify.md","line":22}}}}
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSecurityFindings()).hasSize(2);
        assertThat(projectNode.getSecurityFindings())
                .anyMatch(f -> f.getScanner().equals("TRUFFLEHOG")
                        && f.getSeverity().equals("CRITICAL")
                        && f.getRuleId().equals("AWS"));
        assertThat(projectNode.getSecurityFindings())
                .anyMatch(f -> f.getRuleId().equals("Slack") && f.getSeverity().equals("HIGH"));
        assertThat(result.stats().get("findings")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no scanner reports exist")
    void supportsFalseWithoutReports(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }
}
