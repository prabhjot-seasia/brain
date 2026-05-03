package com.assurant.brain.avenger.hawkeye;

import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsffMapper")
class AsffMapperTest {

    private final com.assurant.brain.config.properties.BrainProperties props =
            new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    private final AsffMapper mapper = new AsffMapper(new ObjectMapper(), props);
    private static final String ARN = "arn:aws:securityhub:::product/project-brain/hawkeye";

    @Test
    @DisplayName("BLOCKED review maps to CRITICAL severity, NEW workflow, FAILED compliance")
    void blockedMapping() {
        AvengerReview review = baseReview(AvengerVerdict.BLOCKED, List.of("hardcoded secret"));

        JsonNode finding = mapper.toFinding(review, ARN);

        assertThat(finding.get("SchemaVersion").asText()).isEqualTo("2018-10-08");
        assertThat(finding.get("ProductArn").asText()).isEqualTo(ARN);
        assertThat(finding.get("Severity").get("Label").asText()).isEqualTo("CRITICAL");
        assertThat(finding.get("Severity").get("Normalized").asInt()).isEqualTo(90);
        assertThat(finding.get("Workflow").get("Status").asText()).isEqualTo("NEW");
        assertThat(finding.get("Compliance").get("Status").asText()).isEqualTo("FAILED");
        assertThat(finding.get("UserDefinedFields").get(0).get("Value").asText()).isEqualTo("hardcoded secret");
    }

    @Test
    @DisplayName("APPROVED review maps to INFORMATIONAL/RESOLVED/PASSED")
    void approvedMapping() {
        AvengerReview review = baseReview(AvengerVerdict.APPROVED, List.of());

        JsonNode finding = mapper.toFinding(review, ARN);

        assertThat(finding.get("Severity").get("Label").asText()).isEqualTo("INFORMATIONAL");
        assertThat(finding.get("Severity").get("Normalized").asInt()).isZero();
        assertThat(finding.get("Workflow").get("Status").asText()).isEqualTo("RESOLVED");
        assertThat(finding.get("Compliance").get("Status").asText()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("toFindingsBatch wraps multiple findings under \"Findings\" array")
    void batch() {
        JsonNode batch = mapper.toFindingsBatch(List.of(
                baseReview(AvengerVerdict.BLOCKED, List.of("a")),
                baseReview(AvengerVerdict.CHANGES_REQUESTED, List.of("b"))), ARN);

        assertThat(batch.get("Findings").isArray()).isTrue();
        assertThat(batch.get("Findings").size()).isEqualTo(2);
        assertThat(batch.get("Findings").get(1).get("Severity").get("Label").asText()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("null createdAt serialized as empty string")
    void nullCreatedAtSerializedAsEmptyString() {
        AvengerReview r = baseReview(AvengerVerdict.APPROVED, List.of());
        r.setCreatedAt(null);

        JsonNode finding = mapper.toFinding(r, ARN);

        assertThat(finding.get("CreatedAt").asText()).isEmpty();
        assertThat(finding.get("UpdatedAt").asText()).isEmpty();
    }

    @Test
    @DisplayName("null projectId mapped to 'unknown-project' resource Id")
    void nullProjectIdMappedToUnknownProject() {
        AvengerReview r = baseReview(AvengerVerdict.APPROVED, List.of());
        r.setProjectId(null);

        JsonNode finding = mapper.toFinding(r, ARN);

        assertThat(finding.get("Resources").get(0).get("Id").asText()).isEqualTo("unknown-project");
    }

    @Test
    @DisplayName("issue text longer than 1024 chars is truncated")
    void longIssueTruncated() {
        String longIssue = "x".repeat(2000);
        AvengerReview r = baseReview(AvengerVerdict.CHANGES_REQUESTED, List.of(longIssue));

        JsonNode finding = mapper.toFinding(r, ARN);

        assertThat(finding.get("UserDefinedFields").get(0).get("Value").asText()).hasSize(1024);
    }

    private AvengerReview baseReview(AvengerVerdict verdict, List<String> issues) {
        AvengerReview r = new AvengerReview();
        r.setId(UUID.randomUUID());
        r.setAvenger(AvengerType.HAWKEYE);
        r.setProjectId("proj-1");
        r.setRequestHash("abc123");
        r.setVerdict(verdict);
        r.setIssues(issues);
        r.setSummary("review summary");
        r.setTokensIn(100);
        r.setTokensOut(50);
        r.setLatencyMs(200);
        r.setCreatedAt(OffsetDateTime.of(2026, 4, 27, 12, 0, 0, 0, ZoneOffset.UTC));
        return r;
    }
}
