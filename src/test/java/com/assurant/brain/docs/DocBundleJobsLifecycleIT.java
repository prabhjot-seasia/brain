package com.assurant.brain.docs;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("DocGeneratorController async + retry + per-type PDF (H3, H4)")
class DocBundleJobsLifecycleIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    FullDocBundleService fullDocBundleService;

    @Test
    @DisplayName("H3a — POST /docs/full/{projectId} returns 202+jobId+streamUrl")
    void fullStartReturnsJobAndStream() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(fullDocBundleService.startGeneration(eq("doc-h3"), eq(false)))
                .thenReturn(new FullDocBundleService.GenerateResult(
                        docId, DocGenerationStatus.GENERATING, false, null, jobId, false));

        mvc.perform(post("/api/v1/docs/full/{p}", "doc-h3"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(docId.toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.streamUrl").value("/api/v1/jobs/stream/" + jobId));

        verify(fullDocBundleService).startGeneration("doc-h3", false);
    }

    @Test
    @DisplayName("H3b — POST /docs/{id}/retry-failed dispatches retry path")
    void retryFailedDispatches() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(fullDocBundleService.retryFailedSections(eq(docId)))
                .thenReturn(new FullDocBundleService.GenerateResult(
                        docId, DocGenerationStatus.GENERATING, false, null, jobId, false));

        mvc.perform(post("/api/v1/docs/{id}/retry-failed", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

        verify(fullDocBundleService).retryFailedSections(docId);
    }

    @Test
    @DisplayName("H4 — GET /docs/{id}/pdf/{docType} serves bytes with security headers")
    void typedPdfServesBytes() throws Exception {
        UUID docId = UUID.randomUUID();
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        when(fullDocBundleService.downloadTypedPdf(eq(docId), eq(DocType.ARCHITECTURE)))
                .thenReturn(Optional.of(pdfBytes));

        mvc.perform(get("/api/v1/docs/{id}/pdf/{docType}", docId, "ARCHITECTURE"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    @DisplayName("H4b — GET /docs/{id}/pdf/{docType} returns 404 when no per-type PDF persisted")
    void typedPdfMissingReturns404() throws Exception {
        UUID docId = UUID.randomUUID();
        when(fullDocBundleService.downloadTypedPdf(any(UUID.class), any(DocType.class)))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/docs/{id}/pdf/{docType}", docId, "EXPLANATION"))
                .andExpect(status().isNotFound());
    }
}
