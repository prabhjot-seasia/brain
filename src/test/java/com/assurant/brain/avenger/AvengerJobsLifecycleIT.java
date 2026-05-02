package com.assurant.brain.avenger;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Avenger async-start endpoints (H1, H2)")
class AvengerJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    AvengerOrchestrator orchestrator;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    AvengerReviewer reviewer;

    @Autowired private AsyncJobRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("avg-h1",
                org.springframework.data.domain.PageRequest.of(0, 50)));
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("avg-h2",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("H1 — POST /avengers/full-review/start returns 202+jobId, dispatches AVENGER_FULL_REVIEW")
    void fullReviewStart() throws Exception {
        String body = "{\"projectId\":\"avg-h1\",\"code\":\"public class Foo {}\",\"context\":null}";

        MvcResult result = mvc.perform(post("/api/v1/avengers/full-review/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.attachedToExisting").value(false))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        String jobId = node.get("jobId").asText();

        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("AVENGER_FULL_REVIEW");
        assertThat(saved.getTargetKind()).isEqualTo("PROJECT_CODEHASH");
        assertThat(saved.getProjectId()).isEqualTo("avg-h1");

        MvcResult second = mvc.perform(post("/api/v1/avengers/full-review/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode secondNode = objectMapper.readTree(second.getResponse().getContentAsString());
        if (secondNode.get("attachedToExisting").asBoolean()) {
            assertThat(secondNode.get("jobId").asText()).isEqualTo(jobId);
        }
    }

    @Test
    @DisplayName("H2 — POST /avengers/{name}/review/start returns 202+jobId, dispatches AVENGER_SINGLE_REVIEW")
    void singleReviewStart() throws Exception {
        String body = "{\"projectId\":\"avg-h2\",\"code\":\"public class Bar {}\",\"context\":null}";

        MvcResult result = mvc.perform(post("/api/v1/avengers/STARK/review/start")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("AVENGER_SINGLE_REVIEW");
        assertThat(saved.getTargetKind()).isEqualTo("AVENGER_CODEHASH");
        assertThat(saved.getTargetId()).startsWith("STARK:avg-h2:");
    }
}
