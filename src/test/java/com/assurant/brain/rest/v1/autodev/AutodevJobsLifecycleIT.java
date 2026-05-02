package com.assurant.brain.rest.v1.autodev;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AutodevController async-start (heavy-op IT)")
class AutodevJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.facade.autodev.AutodevFacade autodevFacade;

    @Autowired private AsyncJobRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.findByProjectIdOrderByCreatedAtDesc(null,
                org.springframework.data.domain.PageRequest.of(0, 50));
        repository.deleteAll(repository.findAll().stream()
                .filter(j -> "autodev-h-execute".equals(j.getTargetId())
                        || "autodev-h-prs".equals(j.getTargetId()))
                .toList());
    }

    @Test
    @DisplayName("POST /autodev/execute/start dispatches AUTODEV_PIPELINE")
    void executeAsync() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "sessionId", "autodev-h-execute",
                "approvedProjectIds", List.of("proj-a", "proj-b")));

        MvcResult result = mvc.perform(post("/api/v1/autodev/execute/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("AUTODEV_PIPELINE");
        assertThat(saved.getTargetKind()).isEqualTo("SESSION");
        assertThat(saved.getTargetId()).isEqualTo("autodev-h-execute");
    }

    @Test
    @DisplayName("POST /autodev/create-prs/start dispatches MULTI_REPO_PR")
    void createPrsAsync() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "sessionId", "autodev-h-prs",
                "repos", List.of(Map.of(
                        "projectId", "proj-a",
                        "repoUrl", "https://github.com/example/a.git",
                        "baseBranch", "main"))));

        MvcResult result = mvc.perform(post("/api/v1/autodev/create-prs/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("MULTI_REPO_PR");
        assertThat(saved.getTargetKind()).isEqualTo("SESSION");
        assertThat(saved.getTargetId()).isEqualTo("autodev-h-prs");
    }
}
