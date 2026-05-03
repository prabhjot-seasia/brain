package com.assurant.brain.ci;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GitHubWebhookController")
class GitHubWebhookControllerTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret-value";

    private CiRemediationService ciRemediationService;
    private PullRequestRecordRepository prRecordRepository;
    private GitHubWebhookController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        ciRemediationService = mock(CiRemediationService.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);
        var ci = new BrainProperties.Ci(WEBHOOK_SECRET, 3, 0.1, 0.1, 3.0, 15000);
        var props = new BrainProperties(null, null, null, null, null, null, null, ci, null, null, null, null, null, null, null, null, null, null, null, null);
        var asyncJobs = mock(com.assurant.brain.jobs.AsyncJobService.class);
        var jobId = java.util.UUID.randomUUID();
        var stubJob = new com.assurant.brain.jobs.AsyncJob(
                jobId, "CI_REMEDIATION", "PR", "pr-id", null,
                com.assurant.brain.jobs.AsyncJobStatus.QUEUED,
                null, null, null, null, null, null, null,
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), false);
        when(asyncJobs.startOrAttach(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(stubJob);
        controller = new GitHubWebhookController(ciRemediationService, prRecordRepository, props, objectMapper, asyncJobs);
    }

    @Test
    @DisplayName("rejects request with invalid signature")
    void rejectsInvalidSignature() {
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook("sha256=invalid", "workflow_run", "{}");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("rejects request with missing signature")
    void rejectsMissingSignature() {
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(null, "workflow_run", "{}");
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("ignores unsupported event types")
    void ignoresUnsupportedEvent() throws Exception {
        String payload = "{}";
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "push", payload);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "ignored");
    }

    @Test
    @DisplayName("ignores non-completed action")
    void ignoresNonCompletedAction() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("action", "requested"));
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "workflow_run", payload);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("reason", "Action not completed");
    }

    @Test
    @DisplayName("ignores non-Brain branches")
    void ignoresNonBrainBranch() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of("id", 123, "conclusion", "failure", "head_branch", "feature/foo")
        ));
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "workflow_run", payload);
        assertThat(response.getBody()).containsEntry("reason", "Not a Brain branch");
    }

    @Test
    @DisplayName("returns success for passing CI on Brain branch")
    void ciPassSuccess() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of("id", 456, "conclusion", "success", "head_branch", "brain/fix-123")
        ));
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "workflow_run", payload);
        assertThat(response.getBody()).containsEntry("status", "success");
    }

    @Test
    @DisplayName("triggers remediation on CI failure for matching PR")
    void triggersRemediation() throws Exception {
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(UUID.randomUUID());
        pr.setBranchName("brain/fix-123");
        pr.setStatus(PrStatus.CREATED);
        pr.setPrNumber(42);
        when(prRecordRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(pr));

        String payload = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of("id", 789, "conclusion", "failure", "head_branch", "brain/fix-123")
        ));
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "workflow_run", payload);
        assertThat(response.getBody()).containsEntry("status", "remediation_triggered");
        assertThat(response.getBody()).containsKey("jobId");
        assertThat(response.getBody()).containsKey("streamUrl");
        verify(ciRemediationService).remediateAsync(eq(pr.getId()), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ignores CI failure when no matching PR found")
    void noMatchingPr() throws Exception {
        when(prRecordRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        String payload = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of("id", 100, "conclusion", "failure", "head_branch", "brain/orphan")
        ));
        String sig = computeSignature(payload);
        ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(sig, "workflow_run", payload);
        assertThat(response.getBody()).containsEntry("reason", "No matching PR record");
    }

    private String computeSignature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hash);
    }
}
