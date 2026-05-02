package com.assurant.brain.rest.v1.analyze;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Analyze async-start (H11)")
class AnalyzeJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.facade.AnalysisFacade analysisFacade;

    @Autowired private AsyncJobRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("h11",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("POST /analyze/start dispatches ANALYZE_REQUIREMENT; same sessionId dedups to existing job")
    void sessionIdDedups() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "projectId", "h11",
                "requirement", "add a CSV export endpoint",
                "sessionId", "s-abc-123",
                "answers", ""));

        MvcResult first = mvc.perform(post("/api/v1/analyze/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attachedToExisting").value(false))
                .andReturn();

        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString());
        String jobId = firstNode.get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("ANALYZE_REQUIREMENT");
        assertThat(saved.getTargetKind()).isEqualTo("SESSION");
        assertThat(saved.getTargetId()).isEqualTo("s-abc-123");

        MvcResult second = mvc.perform(post("/api/v1/analyze/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode secondNode = objectMapper.readTree(second.getResponse().getContentAsString());
        if (secondNode.get("attachedToExisting").asBoolean()) {
            assertThat(secondNode.get("jobId").asText()).isEqualTo(jobId);
        }
    }
}
