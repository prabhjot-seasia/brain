package com.assurant.brain.avenger.hawkeye;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AsffMapper {

    private static final String SCHEMA_VERSION = "2018-10-08";
    private static final String PRODUCT_NAME = "ProjectBrain.HAWKEYE";
    private static final String GENERATOR_ID = "project-brain/avenger/hawkeye";
    private static final String DEFAULT_AWS_ACCOUNT_ID = "000000000000";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String ASFF_TYPE = "Software and Configuration Checks/Vulnerabilities/CVE";
    private static final DateTimeFormatter ASFF_INSTANT = DateTimeFormatter.ISO_INSTANT;

    private final ObjectMapper objectMapper;
    private final BrainProperties brainProperties;

    private String awsAccountId() {
        if (brainProperties.hawkeye() == null
                || brainProperties.hawkeye().awsAccountId() == null
                || brainProperties.hawkeye().awsAccountId().isBlank()) return DEFAULT_AWS_ACCOUNT_ID;
        return brainProperties.hawkeye().awsAccountId();
    }

    private String region() {
        if (brainProperties.hawkeye() == null
                || brainProperties.hawkeye().region() == null
                || brainProperties.hawkeye().region().isBlank()) return DEFAULT_REGION;
        return brainProperties.hawkeye().region();
    }

    public JsonNode toFindingsBatch(List<AvengerReview> reviews, String productArn) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode findings = root.putArray("Findings");
        for (AvengerReview review : reviews) {
            findings.add(toFinding(review, productArn));
        }
        return root;
    }

    public ObjectNode toFinding(AvengerReview review, String productArn) {
        ObjectNode finding = objectMapper.createObjectNode();
        finding.put("SchemaVersion", SCHEMA_VERSION);
        finding.put("Id", "brain/hawkeye/" + review.getId());
        finding.put("ProductArn", productArn);
        finding.put("ProductName", PRODUCT_NAME);
        finding.put("GeneratorId", GENERATOR_ID);
        finding.put("AwsAccountId", awsAccountId());
        finding.putArray("Types").add(ASFF_TYPE);
        finding.put("CreatedAt", review.getCreatedAt() == null ? "" : review.getCreatedAt().toInstant().atOffset(java.time.ZoneOffset.UTC).format(ASFF_INSTANT));
        finding.put("UpdatedAt", review.getCreatedAt() == null ? "" : review.getCreatedAt().toInstant().atOffset(java.time.ZoneOffset.UTC).format(ASFF_INSTANT));
        finding.put("RecordState", "ACTIVE");

        AvengerVerdict verdict = review.getVerdict();
        ObjectNode severity = finding.putObject("Severity");
        severity.put("Label", asffSeverityLabel(verdict));
        severity.put("Normalized", asffNormalized(verdict));

        ObjectNode workflow = finding.putObject("Workflow");
        workflow.put("Status", verdict == AvengerVerdict.APPROVED ? "RESOLVED" : "NEW");

        ObjectNode compliance = finding.putObject("Compliance");
        compliance.put("Status", verdict == AvengerVerdict.APPROVED ? "PASSED" : "FAILED");

        finding.put("Title", "HAWKEYE security review: " + verdict);
        finding.put("Description", review.getSummary() == null ? "" : review.getSummary());

        ArrayNode resources = finding.putArray("Resources");
        ObjectNode resource = resources.addObject();
        resource.put("Type", "Other");
        resource.put("Id", review.getProjectId() == null ? "unknown-project" : review.getProjectId());
        resource.put("Region", region());
        ObjectNode details = resource.putObject("Details");
        ObjectNode other = details.putObject("Other");
        other.put("requestHash", review.getRequestHash() == null ? "" : review.getRequestHash());
        other.put("tokensIn", String.valueOf(review.getTokensIn()));
        other.put("tokensOut", String.valueOf(review.getTokensOut()));
        other.put("latencyMs", String.valueOf(review.getLatencyMs()));

        ArrayNode issues = finding.putArray("UserDefinedFields");
        if (review.getIssues() != null) {
            int idx = 0;
            for (String issue : review.getIssues()) {
                ObjectNode entry = issues.addObject();
                entry.put("Key", "issue_" + (idx++));
                entry.put("Value", truncate(issue, 1024));
            }
        }
        return finding;
    }

    private String asffSeverityLabel(AvengerVerdict verdict) {
        if (verdict == null) return "INFORMATIONAL";
        return switch (verdict) {
            case BLOCKED -> "CRITICAL";
            case CHANGES_REQUESTED -> "HIGH";
            case APPROVED -> "INFORMATIONAL";
        };
    }

    private int asffNormalized(AvengerVerdict verdict) {
        if (verdict == null) return 0;
        return switch (verdict) {
            case BLOCKED -> 90;
            case CHANGES_REQUESTED -> 70;
            case APPROVED -> 0;
        };
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
