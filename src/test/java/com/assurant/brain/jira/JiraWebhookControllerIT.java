package com.assurant.brain.jira;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jira.dao.JiraIssueRunRepository;
import com.assurant.brain.jira.domain.JiraIssueRun;
import com.assurant.brain.jira.enums.JiraRunState;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("JiraWebhookController (UX-Q5)")
@TestPropertySource(properties = {
        "brain.jira.webhook-secret=test-secret",
        "brain.jira.label-prefix=AI_DEV_",
        "brain.jira.connected-user-id=bot"
})
class JiraWebhookControllerIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    JiraDrivenAutodevOrchestrator orchestrator;

    @Autowired private JiraIssueRunRepository runRepository;
    @Autowired private AsyncJobRepository jobRepository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        jobRepository.deleteAll();
        runRepository.deleteAll();
    }

    private String payload(String issueKey, List<String> labels) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "webhookEvent", "jira:issue_updated",
                "issue", Map.of(
                        "key", issueKey,
                        "fields", Map.of("labels", labels))));
    }

    @Test
    @DisplayName("missing token → 401")
    void missingTokenRejected() throws Exception {
        mvc.perform(post("/api/v1/webhooks/jira")
                        .contentType("application/json")
                        .content(payload("BRAIN-1", List.of("AI_DEV_READY"))))
                .andExpect(status().isUnauthorized());
        verify(orchestrator, never()).handle(anyString(), any(), any());
    }

    @Test
    @DisplayName("AI_DEV_READY first time → 202, START_FRESH dispatched, JIRA_AUTODEV job created")
    void firstReadyDispatchesStartFresh() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json")
                        .content(payload("BRAIN-2", List.of("AI_DEV_READY"))))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID jobId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText());

        var saved = jobRepository.findById(jobId).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("JIRA_AUTODEV");
        assertThat(saved.getTargetKind()).isEqualTo("JIRA_ISSUE");
        assertThat(saved.getTargetId()).isEqualTo("BRAIN-2");
        verify(orchestrator).handle("BRAIN-2",
                JiraDrivenAutodevOrchestrator.LabelTransition.START_FRESH, jobId);
    }

    @Test
    @DisplayName("two webhooks for same issue while job in flight → second attaches, no second dispatch")
    void dedupAttachesSecondWebhook() throws Exception {
        mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json")
                        .content(payload("BRAIN-3", List.of("AI_DEV_READY"))))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json")
                        .content(payload("BRAIN-3", List.of("AI_DEV_READY"))))
                .andExpect(status().isAccepted());
        verify(orchestrator, times(1)).handle(anyString(), any(), any());
    }

    @Test
    @DisplayName("non-AI_DEV label change → 200, no job, no dispatch")
    void nonAiDevLabelIgnored() throws Exception {
        mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json")
                        .content(payload("BRAIN-4", List.of("frontend", "needs-design"))))
                .andExpect(status().isOk());
        verify(orchestrator, never()).handle(anyString(), any(), any());
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("AI_DEV_READY after AI_DEV_ANALYSIS_DONE → IMPLEMENT_PLAN")
    void readyAfterAnalysisDoneImplements() throws Exception {
        JiraIssueRun run = new JiraIssueRun();
        run.setIssueKey("BRAIN-5");
        run.setState(JiraRunState.AI_DEV_ANALYSIS_DONE);
        run.setLastLabel("AI_DEV_ANALYSIS_DONE");
        runRepository.save(run);

        MvcResult result = mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json")
                        .content(payload("BRAIN-5", List.of("AI_DEV_READY"))))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID jobId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText());
        verify(orchestrator).handle("BRAIN-5",
                JiraDrivenAutodevOrchestrator.LabelTransition.IMPLEMENT_PLAN, jobId);
    }

    @Test
    @DisplayName("non issue_updated event → 200, no job")
    void otherEventIgnored() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "webhookEvent", "jira:issue_created",
                "issue", Map.of("key", "BRAIN-6", "fields", Map.of("labels", List.of("AI_DEV_READY")))));
        mvc.perform(post("/api/v1/webhooks/jira").param("token", "test-secret")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        assertThat(jobRepository.count()).isZero();
    }
}
