package com.assurant.brain.ci;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("CI_REMEDIATION webhook flow (heavy-op coverage)")
@TestPropertySource(properties = "brain.ci.webhook-secret=test-webhook-secret")
class GitHubWebhookCiRemediationIT extends BrainApplicationTests {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    CiRemediationService ciRemediationService;

    @Autowired private PullRequestRecordRepository prRepo;
    @Autowired private ClarificationSessionRepository sessionRepo;
    @Autowired private AsyncJobRepository jobRepo;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jobRepo.deleteAll();
        prRepo.deleteAll();
        sessionRepo.deleteAll();
    }

    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private PullRequestRecord seedPr(String branch) {
        ClarificationSession session = new ClarificationSession();
        session.setProjectId("ci-h-proj");
        session.setRequirement("test");
        session.setStatus(SessionStatus.COMPLETE);
        session = sessionRepo.save(session);

        PullRequestRecord rec = new PullRequestRecord();
        rec.setSessionId(session.getId());
        rec.setRepoUrl("https://github.com/example/repo.git");
        rec.setBaseBranch("main");
        rec.setBranchName(branch);
        rec.setPrNumber(42);
        rec.setProjectId("ci-h-proj");
        rec.setStatus(PrStatus.CREATED);
        return prRepo.save(rec);
    }

    @Test
    @DisplayName("invalid HMAC signature → 401")
    void invalidSignatureRejected() throws Exception {
        String body = "{}";
        mvc.perform(post("/api/v1/webhooks/github")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .header("X-GitHub-Event", "workflow_run")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("workflow_run with conclusion=failure on a Brain branch dispatches CI_REMEDIATION")
    void failureTriggersRemediation() throws Exception {
        PullRequestRecord pr = seedPr("brain/codegen-99999");
        String body = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of(
                        "id", 12345L,
                        "conclusion", "failure",
                        "head_branch", "brain/codegen-99999")));
        String sig = hmacSha256Hex(body, WEBHOOK_SECRET);

        MvcResult result = mvc.perform(post("/api/v1/webhooks/github")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "workflow_run")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        UUID jobId = UUID.fromString((String) resp.get("jobId"));
        var saved = jobRepo.findById(jobId).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("CI_REMEDIATION");
        assertThat(saved.getTargetKind()).isEqualTo("PR");
        assertThat(saved.getTargetId()).isEqualTo(pr.getId().toString());
        assertThat(saved.getProjectId()).isEqualTo("ci-h-proj");
    }

    @Test
    @DisplayName("workflow_run with conclusion=success on a Brain branch is ignored (no job)")
    void successDoesNotTriggerRemediation() throws Exception {
        seedPr("brain/codegen-99998");
        String body = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of(
                        "id", 12346L,
                        "conclusion", "success",
                        "head_branch", "brain/codegen-99998")));
        String sig = hmacSha256Hex(body, WEBHOOK_SECRET);

        mvc.perform(post("/api/v1/webhooks/github")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "workflow_run")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertThat(jobRepo.count()).as("No CI_REMEDIATION job should be created on success").isZero();
    }

    @Test
    @DisplayName("workflow_run on a non-Brain branch is ignored")
    void nonBrainBranchIgnored() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "action", "completed",
                "workflow_run", Map.of(
                        "id", 12347L,
                        "conclusion", "failure",
                        "head_branch", "main")));
        String sig = hmacSha256Hex(body, WEBHOOK_SECRET);

        mvc.perform(post("/api/v1/webhooks/github")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "workflow_run")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertThat(jobRepo.count()).isZero();
    }
}
