package com.assurant.brain.security;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.docs.FullDocBundleService;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Strict-mode enforcement (M6) — non-member always 403 across project-scoped endpoints")
@TestPropertySource(properties = {
        "brain.security.enforce-project-membership=true"
})
class StrictModeForbiddenIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.docs.FullDocRateLimiter fullDocRateLimiter;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    FullDocBundleService fullDocBundleService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.conventions.RulePackInstaller installer;

    @Autowired private com.assurant.brain.dao.GeneratedDocumentRepository docRepository;
    @Autowired private AsyncJobService asyncJobService;
    @Autowired private com.assurant.brain.jobs.AsyncJobRepository jobRepository;
    @Autowired private ObjectMapper objectMapper;

    private UUID seedDocument(String projectId) {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setProjectId(projectId);
        doc.setTitle("strict-test-" + projectId);
        doc.setDocType(com.assurant.brain.enums.DocType.FULL_PROJECT_PDF);
        doc.setStatus(DocGenerationStatus.GENERATING);
        return docRepository.save(doc).getId();
    }

    @AfterEach
    void cleanup() {
        docRepository.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("docs/full/{projectId} → 403 for non-member")
    void docsFullForbidden() throws Exception {
        mvc.perform(post("/api/v1/docs/full/{p}", "strict-proj"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/{id}/retry-failed → 403 for non-member")
    void docsRetryForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(post("/api/v1/docs/{id}/retry-failed", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/{id} GET → 403 for non-member")
    void docsGetForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(get("/api/v1/docs/{id}", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/{id} DELETE → 403 for non-member")
    void docsDeleteForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(delete("/api/v1/docs/{id}", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/full/status/{id} → 403 for non-member")
    void docsStatusForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(get("/api/v1/docs/full/status/{id}", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/{id}/pdf → 403 for non-member")
    void docsPdfForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(get("/api/v1/docs/{id}/pdf", id))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("docs/{id}/pdf/{docType} → 403 for non-member")
    void docsTypedPdfForbidden() throws Exception {
        UUID id = seedDocument("strict-proj");
        mvc.perform(get("/api/v1/docs/{id}/pdf/{docType}", id, "ARCHITECTURE"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rule-packs/install/start → 403 for non-member (canAdminister)")
    void rulePackInstallForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "version", "1.0.0",
                "pack", Map.of(
                        "id", "p", "version", "1.0.0", "description", "d",
                        "conventions", List.of(Map.of("rule", "x", "category", "y", "trustWeight", 1.0)))));
        mvc.perform(post("/api/v1/projects/{id}/rule-packs/install/start", "strict-proj")
                        .header("X-Brain-Approval", "anything")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("jobs/{id} → 403 for non-member when job is project-scoped")
    void jobsGetForbidden() throws Exception {
        AsyncJob job = asyncJobService.startOrAttach("STRICT_TEST", "PROJECT", "strict-target", "strict-proj");
        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("jobs/stream/{id} → 403 for non-member when job is project-scoped")
    void jobsStreamForbidden() throws Exception {
        AsyncJob job = asyncJobService.startOrAttach("STRICT_TEST", "PROJECT", "strict-stream", "strict-proj");
        mvc.perform(get("/api/v1/jobs/stream/{id}", job.id()))
                .andExpect(status().isForbidden());
    }
}
