package com.assurant.brain.rest.v1.ingest;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("IngestController async-start (heavy-op IT)")
class IngestJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.service.IngestionService ingestionService;

    @Autowired private AsyncJobRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("ing-h",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("POST /projects/ingest dispatches INGEST_PROJECT and returns 202+jobId")
    void ingestKickoff() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "projectId", "ing-h",
                "projectName", "Ingest Heavy",
                "repoUrl", "https://github.com/example/repo.git",
                "branch", "main"));

        MvcResult result = mvc.perform(post("/api/v1/projects/ingest")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("INGEST_PROJECT");
        assertThat(saved.getTargetKind()).isEqualTo("PROJECT");
        assertThat(saved.getTargetId()).isEqualTo("ing-h");
        assertThat(saved.getProjectId()).isEqualTo("ing-h");
    }
}
