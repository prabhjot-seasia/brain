package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.jira.dao.JiraIssueRunRepository;
import com.assurant.brain.jira.domain.JiraIssueRun;
import com.assurant.brain.jira.enums.JiraRunState;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.dto.JobStartResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Log4j2
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class JiraWebhookController {

    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final JiraIssueRunRepository runRepository;
    private final AsyncJobService asyncJobService;
    private final JiraDrivenAutodevOrchestrator orchestrator;

    public static final String TOKEN_HEADER = "X-Brain-Webhook-Token";

    @PostMapping("/jira")
    public ResponseEntity<?> handleJiraWebhook(
            @RequestParam(value = "token", required = false) String tokenParam,
            @RequestHeader(value = TOKEN_HEADER, required = false) String tokenHeader,
            @RequestBody String payload) {

        String token = tokenHeader != null && !tokenHeader.isBlank() ? tokenHeader : tokenParam;
        if (!isAuthorized(token)) {
            log.warn("Jira webhook rejected: missing or invalid token");
            return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
        }
        if (!isJiraPatConfigured()) {
            log.error("Jira webhook received but brain.jira PAT is not configured");
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Jira integration not configured (brain.jira.base-url / email / api-token)"));
        }

        Map<String, Object> body;
        try {
            body = objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid JSON"));
        }

        String event = String.valueOf(body.getOrDefault("webhookEvent", ""));
        if (!"jira:issue_updated".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "event=" + event));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> issue = (Map<String, Object>) body.get("issue");
        if (issue == null) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no issue in payload"));
        }

        String issueKey = String.valueOf(issue.get("key"));
        if (issueKey == null || issueKey.isBlank() || "null".equals(issueKey)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no issue key"));
        }

        Set<String> currentLabels = extractLabels(issue);
        Optional<JiraIssueRun> priorRun = runRepository.findByIssueKey(issueKey);
        JiraDrivenAutodevOrchestrator.LabelTransition transition =
                computeTransition(currentLabels, priorRun);

        if (transition == JiraDrivenAutodevOrchestrator.LabelTransition.IGNORE) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no AI_DEV transition"));
        }

        String firstAffinity = priorRun
                .map(r -> r.getAffinityProjectIds() == null || r.getAffinityProjectIds().isEmpty()
                        ? null : r.getAffinityProjectIds().get(0))
                .orElse(null);

        AsyncJob job = asyncJobService.startOrAttach(
                "JIRA_AUTODEV", "JIRA_ISSUE", issueKey, firstAffinity);
        if (!job.attachedToExisting()) {
            orchestrator.handle(issueKey, transition, job.id());
        }

        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }

    private boolean isAuthorized(String token) {
        BrainProperties.Jira jira = brainProperties.jira();
        String configured = jira == null ? null : jira.webhookSecret();
        if (configured == null || configured.isBlank() || token == null) return false;
        byte[] a = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    private boolean isJiraPatConfigured() {
        BrainProperties.Jira jira = brainProperties.jira();
        if (jira == null) return false;
        return notBlank(jira.baseUrl()) && notBlank(jira.email()) && notBlank(jira.apiToken());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractLabels(Map<String, Object> issue) {
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        if (fields == null) return Set.of();
        Object rawLabels = fields.get("labels");
        if (!(rawLabels instanceof List<?> list)) return Set.of();
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (Object l : list) {
            if (l != null) out.add(l.toString());
        }
        return out;
    }

    private JiraDrivenAutodevOrchestrator.LabelTransition computeTransition(
            Set<String> currentLabels, Optional<JiraIssueRun> priorRun) {

        String prefix = labelPrefix();
        String ready = prefix + "READY";
        String analysis = prefix + "ANALYSIS";

        boolean hasReady = currentLabels.contains(ready);
        boolean hasAnalysis = currentLabels.contains(analysis);

        if (priorRun.isEmpty() && hasReady) {
            return JiraDrivenAutodevOrchestrator.LabelTransition.START_FRESH;
        }
        if (priorRun.isPresent()) {
            JiraRunState prior = priorRun.get().getState();
            if (prior == JiraRunState.AI_DEV_ANALYSIS && hasReady) {
                return JiraDrivenAutodevOrchestrator.LabelTransition.CLARIFY_AGAIN_WITH_COMMENTS;
            }
            if (prior == JiraRunState.AI_DEV_ANALYSIS_DONE && hasReady) {
                return JiraDrivenAutodevOrchestrator.LabelTransition.IMPLEMENT_PLAN;
            }
            if (prior == JiraRunState.AI_DEV_ANALYSIS_DONE && hasAnalysis) {
                return JiraDrivenAutodevOrchestrator.LabelTransition.REDO_ANALYSIS;
            }
            if (prior == JiraRunState.IDLE && hasReady) {
                return JiraDrivenAutodevOrchestrator.LabelTransition.START_FRESH;
            }
        }
        return JiraDrivenAutodevOrchestrator.LabelTransition.IGNORE;
    }

    private String labelPrefix() {
        BrainProperties.Jira jira = brainProperties.jira();
        String pref = jira == null ? null : jira.labelPrefix();
        return pref == null || pref.isBlank() ? "AI_DEV_" : pref;
    }
}
