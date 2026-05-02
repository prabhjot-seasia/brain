package com.assurant.brain.rest.v1.conventions;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("RulePack /install/start approval header (H12)")
@TestPropertySource(properties = "brain.rulepack.approval-token=test-thanos-token")
class RulePackJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.conventions.RulePackInstaller installer;

    @Autowired private AsyncJobRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("h12",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    private String body() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "version", "1.0.0",
                "pack", Map.of(
                        "id", "spring-boot-conventions",
                        "version", "1.0.0",
                        "description", "test pack",
                        "conventions", List.of(Map.of(
                                "rule", "use @Log4j2",
                                "category", "logging",
                                "trustWeight", 1.0)))));
    }

    @Test
    @DisplayName("missing X-Brain-Approval header → 403")
    void missingHeaderForbidden() throws Exception {
        mvc.perform(post("/api/v1/projects/{id}/rule-packs/install/start", "h12")
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("wrong X-Brain-Approval header → 403")
    void wrongHeaderForbidden() throws Exception {
        mvc.perform(post("/api/v1/projects/{id}/rule-packs/install/start", "h12")
                        .header("X-Brain-Approval", "nope")
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("valid X-Brain-Approval header → 202 + jobType=RULE_PACK_INSTALL")
    void validHeaderAccepted() throws Exception {
        var result = mvc.perform(post("/api/v1/projects/{id}/rule-packs/install/start", "h12")
                        .header("X-Brain-Approval", "test-thanos-token")
                        .contentType("application/json").content(body()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andReturn();

        String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("jobId").asText();
        var saved = repository.findById(java.util.UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getJobType()).isEqualTo("RULE_PACK_INSTALL");
        assertThat(saved.getTargetKind()).isEqualTo("PROJECT_PACK");
        assertThat(saved.getProjectId()).isEqualTo("h12");
    }
}
