package com.assurant.brain.ci;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String BRANCH_PREFIX = "brain/";

    private final CiRemediationService ciRemediationService;
    private final PullRequestRecordRepository prRecordRepository;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final AsyncJobService asyncJobService;

    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody String payload) {

        if (!verifySignature(payload, signature)) {
            log.warn("GitHub webhook signature verification failed");
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        }

        if (!"workflow_run".equals(event) && !"check_suite".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Unsupported event: " + event));
        }

        try {
            Map<String, Object> body = objectMapper.readValue(payload, new TypeReference<>() {});
            String action = (String) body.getOrDefault("action", "");

            if (!"completed".equals(action)) {
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Action not completed"));
            }

            return handleWorkflowRun(body);
        } catch (Exception e) {
            log.error("Failed to process GitHub webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Processing failed"));
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> handleWorkflowRun(Map<String, Object> body) {
        Map<String, Object> workflowRun = (Map<String, Object>) body.get("workflow_run");
        if (workflowRun == null) {
            workflowRun = (Map<String, Object>) body.get("check_suite");
        }
        if (workflowRun == null) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "No workflow_run in payload"));
        }

        String conclusion = (String) workflowRun.getOrDefault("conclusion", "");
        long runId = ((Number) workflowRun.get("id")).longValue();
        String headBranch = (String) workflowRun.getOrDefault("head_branch", "");

        if (!headBranch.startsWith(BRANCH_PREFIX)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Not a Brain branch"));
        }

        if ("success".equals(conclusion)) {
            log.info("CI passed for branch={} runId={}", headBranch, runId);
            return ResponseEntity.ok(Map.of("status", "success", "branch", headBranch));
        }

        if (!"failure".equals(conclusion)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Conclusion: " + conclusion));
        }

        List<PullRequestRecord> records = prRecordRepository.findAllByOrderByCreatedAtDesc();
        PullRequestRecord matchingPr = records.stream()
                .filter(r -> headBranch.equals(r.getBranchName()) && r.getStatus() == PrStatus.CREATED)
                .findFirst()
                .orElse(null);

        if (matchingPr == null) {
            log.warn("No matching Brain PR found for branch={}", headBranch);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "No matching PR record"));
        }

        log.info("CI failed for Brain PR #{} — triggering remediation", matchingPr.getPrNumber());
        AsyncJob job = asyncJobService.startOrAttach(
                "CI_REMEDIATION", "PR", matchingPr.getId().toString(), matchingPr.getProjectId());
        if (job.attachedToExisting()) {
            log.info("CI remediation already in flight for PR={} job={}", matchingPr.getId(), job.id());
            return ResponseEntity.ok(Map.of(
                    "status", "remediation_in_flight",
                    "jobId", job.id().toString(),
                    "streamUrl", "/api/v1/jobs/stream/" + job.id()
            ));
        }
        ciRemediationService.remediateAsync(matchingPr.getId(), runId, job.id());
        return ResponseEntity.ok(Map.of(
                "status", "remediation_triggered",
                "jobId", job.id().toString(),
                "streamUrl", "/api/v1/jobs/stream/" + job.id()
        ));
    }

    private boolean verifySignature(String payload, String signature) {
        String secret = brainProperties.ci() != null ? brainProperties.ci().webhookSecret() : null;
        if (secret == null || secret.isBlank()) {
            log.warn("BRAIN_GITHUB_WEBHOOK_SECRET is not configured — rejecting all webhook deliveries");
            return false;
        }
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = SIGNATURE_PREFIX + HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Webhook signature verification error: {}", e.getMessage());
            return false;
        }
    }
}
