package com.assurant.brain.docs;

import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DocGeneratorController")
class DocGeneratorControllerTest {

    private DocGeneratorService docGeneratorService;
    private GeneratedDocumentRepository documentRepository;
    private FullDocBundleService fullDocBundleService;
    private FullDocRateLimiter rateLimiter;
    private DocGeneratorController controller;

    @BeforeEach
    void setup() {
        docGeneratorService = mock(DocGeneratorService.class);
        documentRepository = mock(GeneratedDocumentRepository.class);
        fullDocBundleService = mock(FullDocBundleService.class);
        rateLimiter = mock(FullDocRateLimiter.class);
        when(documentRepository.save(any(GeneratedDocument.class))).thenAnswer(inv -> {
            GeneratedDocument doc = inv.getArgument(0);
            if (doc.getId() == null) doc.setId(UUID.randomUUID());
            return doc;
        });
        var props = mock(com.assurant.brain.config.properties.BrainProperties.class);
        controller = new DocGeneratorController(docGeneratorService, documentRepository,
                fullDocBundleService, rateLimiter, props,
                mock(com.assurant.brain.confluence.ConfluencePublisherService.class));
    }

    @Test
    @DisplayName("generate returns 202 and queues async generation")
    void generateReturns202() {
        ResponseEntity<Map<String, Object>> response = controller.generate(Map.of(
                "projectId", "proj-1",
                "prompt", "Explain auth",
                "type", "EXPLANATION"
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsKey("id");
        assertThat(response.getBody()).containsEntry("status", "PENDING");
        assertThat(response.getBody()).containsEntry("docType", "EXPLANATION");
        verify(docGeneratorService).generateAsync(any(UUID.class));
    }

    @Test
    @DisplayName("returns 400 when projectId is missing")
    void missingProjectId() {
        ResponseEntity<Map<String, Object>> response = controller.generate(Map.of(
                "prompt", "Something"
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("returns 400 when prompt is blank")
    void blankPrompt() {
        ResponseEntity<Map<String, Object>> response = controller.generate(Map.of(
                "projectId", "proj-1",
                "prompt", "   "
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("returns 400 for invalid doc type")
    void invalidDocType() {
        ResponseEntity<Map<String, Object>> response = controller.generate(Map.of(
                "projectId", "proj-1",
                "prompt", "Show something",
                "type", "INVALID_TYPE"
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("validTypes");
    }

    @Test
    @DisplayName("status returns completed doc with content")
    void statusCompleted() {
        UUID id = UUID.randomUUID();
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(id);
        doc.setTitle("Auth Flow");
        doc.setDocType(DocType.EXPLANATION);
        doc.setStatus(DocGenerationStatus.COMPLETED);
        doc.setContentMd("# Auth\nContent here");
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        ResponseEntity<Map<String, Object>> response = controller.getStatus(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
        assertThat(response.getBody()).containsKey("contentMd");
    }

    @Test
    @DisplayName("status returns failed doc with error")
    void statusFailed() {
        UUID id = UUID.randomUUID();
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(id);
        doc.setTitle("Bad doc");
        doc.setDocType(DocType.ARCHITECTURE);
        doc.setStatus(DocGenerationStatus.FAILED);
        doc.setErrorMessage("LLM timeout");
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));

        ResponseEntity<Map<String, Object>> response = controller.getStatus(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "FAILED");
        assertThat(response.getBody()).containsEntry("error", "LLM timeout");
    }

    @Test
    @DisplayName("status returns 404 for missing document")
    void statusNotFound() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> response = controller.getStatus(id);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("lists documents by project")
    void listsByProject() {
        when(documentRepository.findByProjectIdOrderByCreatedAtDesc("proj-1")).thenReturn(List.of());
        ResponseEntity<List<GeneratedDocument>> response = controller.listByProject("proj-1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("gets document by id")
    void getsById() {
        UUID id = UUID.randomUUID();
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(id);
        when(documentRepository.findById(id)).thenReturn(Optional.of(doc));
        ResponseEntity<GeneratedDocument> response = controller.getById(id);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("returns 404 for missing document by id")
    void notFound() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());
        ResponseEntity<GeneratedDocument> response = controller.getById(id);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("generateFull returns 202 with documentId when fresh")
    void generateFullFresh() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.startGeneration("proj-1"))
                .thenReturn(new FullDocBundleService.GenerateResult(id, DocGenerationStatus.GENERATING, false, null));
        var resp = controller.generateFull("proj-1", false);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        var body = (com.assurant.brain.docs.dto.FullDocStartResponse) resp.getBody();
        assertThat(body.documentId()).isEqualTo(id);
        assertThat(body.fromCache()).isFalse();
    }

    @Test
    @DisplayName("generateFull returns 200 with fromCache:true on cache hit")
    void generateFullCacheHit() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.startGeneration("proj-1"))
                .thenReturn(new FullDocBundleService.GenerateResult(id, DocGenerationStatus.COMPLETED, true,
                        java.time.OffsetDateTime.now()));
        var resp = controller.generateFull("proj-1", false);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var body = (com.assurant.brain.docs.dto.FullDocStartResponse) resp.getBody();
        assertThat(body.fromCache()).isTrue();
        assertThat(body.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("retryFailed delegates to service and returns 202")
    void retryFailedDelegates() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.retryFailedSections(id))
                .thenReturn(new FullDocBundleService.GenerateResult(id, DocGenerationStatus.GENERATING, false, null));
        var resp = controller.retryFailed(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        var body = (com.assurant.brain.docs.dto.FullDocStartResponse) resp.getBody();
        assertThat(body.documentId()).isEqualTo(id);
    }

    @Test
    @DisplayName("fullStatus returns 404 when service returns empty")
    void fullStatusNotFound() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.status(id)).thenReturn(Optional.empty());
        var resp = controller.fullStatus(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("pdf returns bytes with security headers attached")
    void pdfReturnsBytesAndHeaders() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.downloadPdf(id)).thenReturn(Optional.of(new byte[]{'%', 'P', 'D', 'F'}));
        var resp = controller.pdf(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        var headers = resp.getHeaders();
        assertThat(headers.getCacheControl()).contains("private").contains("no-store");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    @DisplayName("pdf returns 404 when bundle not generated yet")
    void pdfNotFound() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.downloadPdf(id)).thenReturn(Optional.empty());
        var resp = controller.pdf(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("generateFull returns 429 when rate-limit exceeded")
    void generateFullRateLimited() {
        org.mockito.Mockito.doThrow(new FullDocRateLimiter.RateLimitExceededException("burst"))
                .when(rateLimiter).check("full:proj-1");
        var resp = controller.generateFull("proj-1", false);
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("pdf returns 413 when bundle exceeds size cap")
    void pdfOversized() {
        UUID id = UUID.randomUUID();
        when(fullDocBundleService.downloadPdf(id))
                .thenReturn(Optional.of(new byte[51 * 1024 * 1024]));
        var resp = controller.pdf(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    @DisplayName("listFullDocsByProject simplifies row shape and returns size bytes")
    void listFullDocsByProjectShape() {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(UUID.randomUUID());
        doc.setStatus(DocGenerationStatus.COMPLETED);
        doc.setContentPdf(new byte[]{1, 2, 3});
        doc.setGeneratedAt(java.time.OffsetDateTime.now());
        when(fullDocBundleService.historyForProject("proj-1")).thenReturn(List.of(doc));
        var resp = controller.listFullDocsByProject("proj-1");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
        var row = resp.getBody().get(0);
        assertThat(row.sizeBytes()).isEqualTo(3);
        assertThat(row.status()).isEqualTo(DocGenerationStatus.COMPLETED);
    }
}
