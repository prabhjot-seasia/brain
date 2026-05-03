package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.docs.FullDocBundleAggregator.FullDocAggregate;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import com.assurant.brain.graph.node.ProjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FullDocBundleService")
class FullDocBundleServiceTest {

    private GeneratedDocumentRepository repository;
    private FullDocBundleAggregator aggregator;
    private DocGeneratorService docGeneratorService;
    private MermaidPreRenderer mermaidPreRenderer;
    private MarkdownPdfRenderer markdownPdfRenderer;
    private FullDocBundleService service;

    @BeforeEach
    void setup() throws IOException {
        repository = mock(GeneratedDocumentRepository.class);
        aggregator = mock(FullDocBundleAggregator.class);
        docGeneratorService = mock(DocGeneratorService.class);
        mermaidPreRenderer = mock(MermaidPreRenderer.class);
        markdownPdfRenderer = mock(MarkdownPdfRenderer.class);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, new BrainProperties.Docs("/nonexistent/mmdc", 60, 0, 0, 5), null);
        Executor directExecutor = Runnable::run;
        var pdfRepo = mock(com.assurant.brain.dao.GeneratedDocumentPdfRepository.class);
        when(pdfRepo.findByDocumentIdOrderByDocTypeAsc(any(java.util.UUID.class))).thenReturn(java.util.List.of());
        when(pdfRepo.findByDocumentIdAndDocType(any(java.util.UUID.class), any())).thenReturn(Optional.empty());
        var asyncJobs = mock(com.assurant.brain.jobs.AsyncJobService.class);
        var jobEvents = mock(com.assurant.brain.jobs.JobEventPublisher.class);
        java.util.UUID stubJobId = java.util.UUID.randomUUID();
        when(asyncJobs.startOrAttach(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new com.assurant.brain.jobs.AsyncJob(stubJobId, inv.getArgument(0),
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        com.assurant.brain.jobs.AsyncJobStatus.QUEUED,
                        "test", null, null, (short) 0, null, null, null, null, null, false));
        when(asyncJobs.markRunning(any(), anyString())).thenAnswer(inv -> stubJob(inv.getArgument(0), com.assurant.brain.jobs.AsyncJobStatus.RUNNING));
        when(asyncJobs.updateProgress(any(), anyInt(), anyString())).thenAnswer(inv -> stubJob(inv.getArgument(0), com.assurant.brain.jobs.AsyncJobStatus.RUNNING));
        when(asyncJobs.markSucceeded(any(), any())).thenAnswer(inv -> stubJob(inv.getArgument(0), com.assurant.brain.jobs.AsyncJobStatus.SUCCEEDED));
        when(asyncJobs.markPartial(any(), any())).thenAnswer(inv -> stubJob(inv.getArgument(0), com.assurant.brain.jobs.AsyncJobStatus.PARTIAL));
        when(asyncJobs.markFailed(any(), anyString())).thenAnswer(inv -> stubJob(inv.getArgument(0), com.assurant.brain.jobs.AsyncJobStatus.FAILED));
        var symbolValidator = mock(com.assurant.brain.codegen.SymbolGroundingValidator.class);
        when(symbolValidator.validateMarkdown(anyString(), any()))
                .thenReturn(com.assurant.brain.codegen.SymbolGroundingValidator.GroundingResult.clean());
        var symbolDictBuilder = mock(com.assurant.brain.codegen.SymbolDictionaryBuilder.class);
        when(symbolDictBuilder.build(anyString())).thenReturn(com.assurant.brain.codegen.SymbolDictionary.EMPTY);
        service = new FullDocBundleService(repository, pdfRepo, aggregator, docGeneratorService,
                mermaidPreRenderer, markdownPdfRenderer, props, new ObjectMapper(), directExecutor,
                asyncJobs, jobEvents, symbolValidator, symbolDictBuilder);

        when(aggregator.aggregate(anyString())).thenReturn(emptyAggregate("proj-1"));
        when(repository.findFirstByProjectIdAndDocTypeAndStatus(anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findCachedFullDoc(anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(List.of());
        java.util.Map<UUID, GeneratedDocument> store = new java.util.concurrent.ConcurrentHashMap<>();
        when(repository.save(any(GeneratedDocument.class)))
                .thenAnswer(i -> {
                    GeneratedDocument d = i.getArgument(0);
                    if (d.getId() == null) d.setId(UUID.randomUUID());
                    store.put(d.getId(), d);
                    return d;
                });
        when(repository.findById(any(UUID.class)))
                .thenAnswer(i -> Optional.ofNullable(store.get(i.<UUID>getArgument(0))));
        when(mermaidPreRenderer.preRender(anyString()))
                .thenAnswer(i -> new MermaidPreRenderer.PreRenderResult(i.getArgument(0), java.util.Map.of()));
        when(markdownPdfRenderer.markdownToHtml(anyString())).thenAnswer(i -> "<html>" + i.getArgument(0) + "</html>");
        when(markdownPdfRenderer.render(anyString(), anyString())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});
    }

    private static com.assurant.brain.jobs.AsyncJob stubJob(java.util.UUID id, com.assurant.brain.jobs.AsyncJobStatus status) {
        return new com.assurant.brain.jobs.AsyncJob(id, "FULL_DOC_BUNDLE", "PROJECT", "p", "p", status,
                "test", null, null, (short) 0, null, null, null, null, null, false);
    }

    @Test
    @DisplayName("startGeneration with no in-flight + no cache spawns async job and persists row")
    void startGenerationKicksOffJob() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# section\nbody");

        var result = service.startGeneration("proj-1");

        assertThat(result.documentId()).isNotNull();
        verify(docGeneratorService, times(5)).generate(eq("proj-1"), eq(""), any(DocType.class));
        verify(repository, times(2)).save(any(GeneratedDocument.class));
        var stored = repository.findById(result.documentId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(DocGenerationStatus.COMPLETED);
        assertThat(stored.getContentPdf()).isNotNull().hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("startGeneration returns existing in-flight document with 'GENERATING' status (soft lock)")
    void inFlightSoftLock() {
        GeneratedDocument inFlight = new GeneratedDocument();
        inFlight.setId(UUID.randomUUID());
        inFlight.setProjectId("proj-1");
        inFlight.setDocType(DocType.FULL_PROJECT_PDF);
        inFlight.setStatus(DocGenerationStatus.GENERATING);
        when(repository.findFirstByProjectIdAndDocTypeAndStatus(eq("proj-1"), eq(DocType.FULL_PROJECT_PDF), eq(DocGenerationStatus.GENERATING)))
                .thenReturn(Optional.of(inFlight));

        var asyncJobs = (com.assurant.brain.jobs.AsyncJobService)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "asyncJobService");
        when(asyncJobs.startOrAttach(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new com.assurant.brain.jobs.AsyncJob(UUID.randomUUID(), "FULL_DOC_BUNDLE", "PROJECT",
                        "proj-1", "proj-1", com.assurant.brain.jobs.AsyncJobStatus.RUNNING,
                        "test", null, null, (short) 0, null, null, null, null, null, true));

        var result = service.startGeneration("proj-1");

        assertThat(result.documentId()).isEqualTo(inFlight.getId());
        assertThat(result.status()).isEqualTo(DocGenerationStatus.GENERATING);
        assertThat(result.attachedToExisting()).isTrue();
        verify(docGeneratorService, never()).generate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("startGeneration returns cached document when content-hash + TTL match")
    void cacheHitShortCircuits() {
        GeneratedDocument cached = new GeneratedDocument();
        cached.setId(UUID.randomUUID());
        cached.setStatus(DocGenerationStatus.COMPLETED);
        cached.setGeneratedAt(OffsetDateTime.now());
        when(repository.findCachedFullDoc(anyString(), any(), anyString(), any(), anyList()))
                .thenReturn(List.of(cached));

        var result = service.startGeneration("proj-1");

        assertThat(result.fromCache()).isTrue();
        assertThat(result.documentId()).isEqualTo(cached.getId());
        verify(docGeneratorService, never()).generate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("partial failure produces PARTIAL status with surviving sections present")
    void partialFailureMarksPartial() {
        when(docGeneratorService.generate(eq("proj-1"), eq(""), eq(DocType.ARCHITECTURE)))
                .thenThrow(new RuntimeException("LLM down"));
        when(docGeneratorService.generate(eq("proj-1"), eq(""), eq(DocType.SEQUENCE_DIAGRAM)))
                .thenReturn("seq content");
        when(docGeneratorService.generate(eq("proj-1"), eq(""), eq(DocType.CLASS_DIAGRAM)))
                .thenReturn("class content");
        when(docGeneratorService.generate(eq("proj-1"), eq(""), eq(DocType.FLOW_DIAGRAM)))
                .thenReturn("flow content");
        when(docGeneratorService.generate(eq("proj-1"), eq(""), eq(DocType.EXPLANATION)))
                .thenReturn("explanation content");

        var result = service.startGeneration("proj-1");

        var stored = repository.findById(result.documentId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(DocGenerationStatus.PARTIAL);
        assertThat(stored.getSectionResults()).contains("FAILED").contains("OK");
        assertThat(stored.getContentMd()).contains("Section unavailable");
    }

    @Test
    @DisplayName("retryFailedSections rejects when bundle is COMPLETED")
    void retryRejectsWhenComplete() {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(UUID.randomUUID());
        doc.setProjectId("proj-1");
        doc.setDocType(DocType.FULL_PROJECT_PDF);
        doc.setStatus(DocGenerationStatus.COMPLETED);
        doc.setSectionResults("{\"ARCHITECTURE\":\"OK\"}");
        when(repository.findById(doc.getId())).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.retryFailedSections(doc.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already complete");
    }

    @Test
    @DisplayName("retryFailedSections rejects when bundle is GENERATING")
    void retryRejectsWhenInFlight() {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(UUID.randomUUID());
        doc.setProjectId("proj-1");
        doc.setDocType(DocType.FULL_PROJECT_PDF);
        doc.setStatus(DocGenerationStatus.GENERATING);
        when(repository.findById(doc.getId())).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.retryFailedSections(doc.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already generating");
    }

    @Test
    @DisplayName("retryFailedSections regenerates and flips PARTIAL → COMPLETED")
    void retryFailedSuccess() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# section\nbody");
        var first = service.startGeneration("proj-r");
        GeneratedDocument doc = repository.findById(first.documentId()).orElseThrow();
        doc.setStatus(DocGenerationStatus.PARTIAL);
        doc.setSectionResults("{\"ARCHITECTURE\":\"FAILED\",\"SEQUENCE_DIAGRAM\":\"OK\","
                + "\"CLASS_DIAGRAM\":\"OK\",\"FLOW_DIAGRAM\":\"OK\",\"EXPLANATION\":\"OK\"}");
        doc.setSectionContents("{\"SEQUENCE_DIAGRAM\":\"# seq\",\"CLASS_DIAGRAM\":\"# cls\","
                + "\"FLOW_DIAGRAM\":\"# flow\",\"EXPLANATION\":\"# exp\"}");
        repository.save(doc);

        var retry = service.retryFailedSections(doc.getId());

        assertThat(retry.status()).isEqualTo(DocGenerationStatus.GENERATING);
        GeneratedDocument after = repository.findById(doc.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DocGenerationStatus.COMPLETED);
        assertThat(after.getContentPdf()).isNotEmpty();
        // Only the failed section was regenerated — the surviving 4 came from section_contents.
        verify(docGeneratorService, times(6)).generate(anyString(), eq(""), any());
    }

    @Test
    @DisplayName("PDF render exception flips status to FAILED with errorMessage")
    void renderFailureMarksFailed() throws IOException {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        when(markdownPdfRenderer.render(anyString(), anyString()))
                .thenThrow(new IOException("flying saucer kaboom"));

        var result = service.startGeneration("proj-fail");

        GeneratedDocument doc = repository.findById(result.documentId()).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocGenerationStatus.FAILED);
        assertThat(doc.getErrorMessage()).contains("flying saucer kaboom");
    }

    @Test
    @DisplayName("identical aggregates produce identical content hashes (cache lookup determinism)")
    void hashIsDeterministic() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        ArgumentCaptor<String> hash1 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash2 = ArgumentCaptor.forClass(String.class);

        service.startGeneration("proj-h1");
        verify(repository, times(1)).findCachedFullDoc(eq("proj-h1"), any(), hash1.capture(), any(), anyList());

        service.startGeneration("proj-h1");
        verify(repository, times(2)).findCachedFullDoc(eq("proj-h1"), any(), hash2.capture(), any(), anyList());

        assertThat(hash1.getValue()).isEqualTo(hash2.getAllValues().get(1));
        assertThat(hash1.getValue()).hasSize(64);  // SHA-256 hex
    }

    @Test
    @DisplayName("different projects produce different hashes")
    void hashDiffersByProject() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);

        when(aggregator.aggregate("proj-A")).thenReturn(emptyAggregate("proj-A"));
        when(aggregator.aggregate("proj-B")).thenReturn(emptyAggregate("proj-B"));
        service.startGeneration("proj-A");
        service.startGeneration("proj-B");

        verify(repository, times(2)).findCachedFullDoc(anyString(), any(), hashes.capture(), any(), anyList());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    @DisplayName("status() returns FullDocStatusResponse with cacheHorizon for completed doc")
    void statusReturnsResponseWithHorizon() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        var first = service.startGeneration("proj-status");
        var resp = service.status(first.documentId());
        assertThat(resp).isPresent();
        assertThat(resp.get().id()).isEqualTo(first.documentId());
        assertThat(resp.get().projectId()).isEqualTo("proj-status");
        assertThat(resp.get().cacheHorizon()).isAfter(resp.get().generatedAt());
        assertThat(resp.get().sectionResults()).isNotEmpty();
    }

    @Test
    @DisplayName("status() returns empty for unknown documentId")
    void statusEmptyForUnknown() {
        assertThat(service.status(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("downloadPdf returns bytes for COMPLETED doc")
    void downloadPdfReturnsBytes() {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        var first = service.startGeneration("proj-pdf");
        var bytes = service.downloadPdf(first.documentId());
        assertThat(bytes).isPresent();
        assertThat(bytes.get()).isNotEmpty();
    }

    @Test
    @DisplayName("downloadPdf returns empty for unknown documentId")
    void downloadPdfEmptyForUnknown() {
        assertThat(service.downloadPdf(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("downloadPdf returns empty for FAILED doc (status filter)")
    void downloadPdfEmptyForFailed() throws IOException {
        when(docGeneratorService.generate(anyString(), eq(""), any())).thenReturn("# x");
        when(markdownPdfRenderer.render(anyString(), anyString()))
                .thenThrow(new IOException("render fail"));
        var first = service.startGeneration("proj-fail");
        assertThat(service.downloadPdf(first.documentId())).isEmpty();
    }

    @Test
    @DisplayName("historyForProject returns rows for the given projectId")
    void historyForProject() {
        GeneratedDocument doc1 = new GeneratedDocument();
        doc1.setId(UUID.randomUUID());
        doc1.setProjectId("proj-h");
        doc1.setDocType(DocType.FULL_PROJECT_PDF);
        doc1.setStatus(DocGenerationStatus.COMPLETED);
        when(repository.findByProjectIdAndDocTypeOrderByCreatedAtDesc("proj-h", DocType.FULL_PROJECT_PDF))
                .thenReturn(List.of(doc1));
        var history = service.historyForProject("proj-h");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getId()).isEqualTo(doc1.getId());
    }

    @Test
    @DisplayName("retryFailedSections rejects when document is not FULL_PROJECT_PDF")
    void retryRejectsWrongDocType() {
        GeneratedDocument other = new GeneratedDocument();
        other.setId(UUID.randomUUID());
        other.setDocType(DocType.EXPLANATION);
        other.setStatus(DocGenerationStatus.PARTIAL);
        other.setProjectId("proj-x");
        when(repository.findById(other.getId())).thenReturn(java.util.Optional.of(other));

        assertThatThrownBy(() -> service.retryFailedSections(other.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FULL_PROJECT_PDF");
    }

    @Test
    @DisplayName("retryFailedSections throws IllegalStateException when no failed sections")
    void retryRejectsAllOk() {
        GeneratedDocument doc = new GeneratedDocument();
        doc.setId(UUID.randomUUID());
        doc.setDocType(DocType.FULL_PROJECT_PDF);
        doc.setStatus(DocGenerationStatus.PARTIAL);
        doc.setProjectId("proj-allok");
        doc.setSectionResults("{\"ARCHITECTURE\":\"OK\",\"SEQUENCE_DIAGRAM\":\"OK\","
                + "\"CLASS_DIAGRAM\":\"OK\",\"FLOW_DIAGRAM\":\"OK\",\"EXPLANATION\":\"OK\"}");
        when(repository.findById(doc.getId())).thenReturn(java.util.Optional.of(doc));

        assertThatThrownBy(() -> service.retryFailedSections(doc.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no failed sections");
    }

    @Test
    @DisplayName("retryFailedSections throws when document not found")
    void retryRejectsMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.retryFailedSections(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("startGeneration rejects null/blank projectId")
    void startRejectsBlankId() {
        assertThatThrownBy(() -> service.startGeneration(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startGeneration(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startGeneration("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FullDocAggregate emptyAggregate(String projectId) {
        ProjectNode pn = new ProjectNode();
        pn.setId(projectId);
        pn.setName(projectId);
        return new FullDocAggregate(pn, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
