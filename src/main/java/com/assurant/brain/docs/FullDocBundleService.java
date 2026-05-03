package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentPdfRepository;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.docs.FullDocBundleAggregator.FullDocAggregate;
import com.assurant.brain.docs.dto.FullDocStatusResponse;
import com.assurant.brain.docs.dto.SectionResult;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.domain.GeneratedDocumentPdf;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.JobEvent;
import com.assurant.brain.jobs.JobEventPublisher;
import com.assurant.brain.graph.node.CommunitySummaryNode;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SLONode;
import com.assurant.brain.graph.node.TestRunNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

@Log4j2
@Service
public class FullDocBundleService {

    private static final List<DocType> SECTIONS = List.of(
            DocType.ARCHITECTURE,
            DocType.SEQUENCE_DIAGRAM,
            DocType.CLASS_DIAGRAM,
            DocType.FLOW_DIAGRAM,
            DocType.EXPLANATION);

    private final GeneratedDocumentRepository repository;
    private final GeneratedDocumentPdfRepository pdfRepository;
    private final FullDocBundleAggregator aggregator;
    private final DocGeneratorService docGeneratorService;
    private final MermaidPreRenderer mermaidPreRenderer;
    private final MarkdownPdfRenderer markdownPdfRenderer;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final Executor brainLlmExecutor;
    private final AsyncJobService asyncJobService;
    private final JobEventPublisher jobEventPublisher;
    private final com.assurant.brain.codegen.SymbolGroundingValidator symbolGroundingValidator;
    private final com.assurant.brain.codegen.SymbolDictionaryBuilder symbolDictionaryBuilder;

    public static final String JOB_TYPE_FULL = "FULL_DOC_BUNDLE";
    public static final String JOB_TYPE_RETRY = "FULL_DOC_BUNDLE_RETRY";

    private final ConcurrentMap<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

    public FullDocBundleService(GeneratedDocumentRepository repository,
                                 GeneratedDocumentPdfRepository pdfRepository,
                                 FullDocBundleAggregator aggregator,
                                 DocGeneratorService docGeneratorService,
                                 MermaidPreRenderer mermaidPreRenderer,
                                 MarkdownPdfRenderer markdownPdfRenderer,
                                 BrainProperties brainProperties,
                                 ObjectMapper objectMapper,
                                 @Qualifier("brainLlmExecutor") Executor brainLlmExecutor,
                                 AsyncJobService asyncJobService,
                                 JobEventPublisher jobEventPublisher,
                                 com.assurant.brain.codegen.SymbolGroundingValidator symbolGroundingValidator,
                                 com.assurant.brain.codegen.SymbolDictionaryBuilder symbolDictionaryBuilder) {
        this.repository = repository;
        this.pdfRepository = pdfRepository;
        this.aggregator = aggregator;
        this.docGeneratorService = docGeneratorService;
        this.mermaidPreRenderer = mermaidPreRenderer;
        this.markdownPdfRenderer = markdownPdfRenderer;
        this.brainProperties = brainProperties;
        this.objectMapper = objectMapper;
        this.brainLlmExecutor = brainLlmExecutor;
        this.asyncJobService = asyncJobService;
        this.jobEventPublisher = jobEventPublisher;
        this.symbolGroundingValidator = symbolGroundingValidator;
        this.symbolDictionaryBuilder = symbolDictionaryBuilder;
    }

    private ReentrantLock lockFor(String projectId) {
        return projectLocks.computeIfAbsent(projectId, k -> new ReentrantLock());
    }

    public record GenerateResult(UUID documentId, DocGenerationStatus status, boolean fromCache,
                                  OffsetDateTime generatedAt, UUID jobId, boolean attachedToExisting) {

        public GenerateResult(UUID documentId, DocGenerationStatus status, boolean fromCache,
                              OffsetDateTime generatedAt) {
            this(documentId, status, fromCache, generatedAt, null, false);
        }
    }

    public GenerateResult startGeneration(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        return startGeneration(projectId, false);
    }

    public GenerateResult startGeneration(String projectId, boolean forceRefresh) {
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            return startGenerationLocked(projectId, forceRefresh);
        } finally {
            lock.unlock();
        }
    }

    private GenerateResult startGenerationLocked(String projectId, boolean forceRefresh) {
        AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE_FULL, "PROJECT", projectId, projectId);
        if (job.attachedToExisting()) {
            Optional<GeneratedDocument> inFlight = repository.findFirstByProjectIdAndDocTypeAndStatus(
                    projectId, DocType.FULL_PROJECT_PDF, DocGenerationStatus.GENERATING);
            UUID docId = inFlight.map(GeneratedDocument::getId).orElse(null);
            log.info("Full-doc attach: existing job={} document={}", job.id(), docId);
            return new GenerateResult(docId, DocGenerationStatus.GENERATING, false,
                    inFlight.map(GeneratedDocument::getGeneratedAt).orElse(null),
                    job.id(), true);
        }

        FullDocAggregate aggregate = aggregator.aggregate(projectId);
        String hash = computeHash(aggregate);

        OffsetDateTime since = OffsetDateTime.now().minusMinutes(cacheTtlMinutes());
        List<GeneratedDocument> cached = forceRefresh
                ? List.of()
                : repository.findCachedFullDoc(
                        projectId, DocType.FULL_PROJECT_PDF, hash, since,
                        List.of(DocGenerationStatus.COMPLETED, DocGenerationStatus.PARTIAL));
        if (forceRefresh) {
            log.info("Full-doc forceRefresh=true — skipping cache lookup for project={}", projectId);
        }
        if (!cached.isEmpty()) {
            GeneratedDocument hit = cached.get(0);
            log.info("Full-doc cache hit for project={} document={} hash={}", projectId, hit.getId(), hash);
            asyncJobService.markSucceeded(job.id(), Map.of(
                    "documentId", hit.getId(),
                    "fromCache", true,
                    "status", hit.getStatus()));
            return new GenerateResult(hit.getId(), hit.getStatus(), true, hit.getGeneratedAt(), job.id(), false);
        }

        GeneratedDocument doc = new GeneratedDocument();
        doc.setProjectId(projectId);
        doc.setTitle("Full Project Documentation: " + projectId);
        doc.setDocType(DocType.FULL_PROJECT_PDF);
        doc.setStatus(DocGenerationStatus.GENERATING);
        doc.setContentHash(hash);
        doc.setSectionResults(initialSectionResults());
        GeneratedDocument saved = repository.save(doc);

        UUID id = saved.getId();
        UUID jobId = job.id();
        CompletableFuture.runAsync(() -> generateAsync(id, projectId, aggregate, SECTIONS, jobId), brainLlmExecutor);
        return new GenerateResult(id, DocGenerationStatus.GENERATING, false, null, jobId, false);
    }

    public GenerateResult retryFailedSections(UUID documentId) {
        GeneratedDocument loaded = repository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
        ReentrantLock lock = lockFor(loaded.getProjectId());
        lock.lock();
        try {
            return retryFailedSectionsLocked(documentId);
        } finally {
            lock.unlock();
        }
    }

    private GenerateResult retryFailedSectionsLocked(UUID documentId) {
        GeneratedDocument doc = repository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
        if (doc.getDocType() != DocType.FULL_PROJECT_PDF) {
            throw new IllegalArgumentException("document is not a FULL_PROJECT_PDF");
        }
        if (doc.getStatus() == DocGenerationStatus.GENERATING) {
            throw new IllegalStateException("document is already generating");
        }
        if (doc.getStatus() == DocGenerationStatus.COMPLETED) {
            throw new IllegalStateException("document is already complete; nothing to retry");
        }

        Map<String, String> existing = parseSectionMap(doc.getSectionResults());
        List<DocType> failedTypes = SECTIONS.stream()
                .filter(t -> !"OK".equals(existing.getOrDefault(t.name(), "PENDING")))
                .toList();
        if (failedTypes.isEmpty()) {
            throw new IllegalStateException("no failed sections to retry");
        }

        doc.setStatus(DocGenerationStatus.GENERATING);
        Map<String, String> resetStatus = new LinkedHashMap<>(existing);
        failedTypes.forEach(t -> resetStatus.put(t.name(), "PENDING"));
        doc.setSectionResults(serializeSectionMap(resetStatus));
        repository.save(doc);
        FullDocAggregate aggregate = aggregator.aggregate(doc.getProjectId());

        AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE_RETRY, "DOCUMENT",
                documentId.toString(), doc.getProjectId());
        UUID id = doc.getId();
        UUID jobId = job.id();
        if (!job.attachedToExisting()) {
            CompletableFuture.runAsync(() ->
                    generateAsync(id, doc.getProjectId(), aggregate, failedTypes, jobId), brainLlmExecutor);
        }
        return new GenerateResult(id, DocGenerationStatus.GENERATING, false, null, jobId, job.attachedToExisting());
    }

    public Optional<FullDocStatusResponse> status(UUID documentId) {
        return repository.findById(documentId).map(this::toStatusResponse);
    }

    public List<GeneratedDocument> historyForProject(String projectId) {
        return repository.findByProjectIdAndDocTypeOrderByCreatedAtDesc(projectId, DocType.FULL_PROJECT_PDF);
    }

    public Optional<byte[]> downloadPdf(UUID documentId) {
        return repository.findById(documentId)
                .filter(d -> d.getStatus() == DocGenerationStatus.COMPLETED
                        || d.getStatus() == DocGenerationStatus.PARTIAL)
                .map(GeneratedDocument::getContentPdf);
    }

    public Optional<byte[]> downloadTypedPdf(UUID documentId, DocType docType) {
        return pdfRepository.findByDocumentIdAndDocType(documentId, docType)
                .map(GeneratedDocumentPdf::getContentPdf);
    }

    private void generateAsync(UUID documentId, String projectId, FullDocAggregate aggregate,
                                List<DocType> typesToGenerate, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Generating sections...");
            GeneratedDocument doc = repository.findById(documentId).orElseThrow();
            Map<String, String> resultMap = parseSectionMap(doc.getSectionResults());

            List<CompletableFuture<SectionResult>> futures = new ArrayList<>();
            for (DocType type : typesToGenerate) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> safeGenerateSection(projectId, type), brainLlmExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            Map<String, String> mergedMap = new LinkedHashMap<>(resultMap);
            Map<DocType, String> contentByType = readExistingSectionContent(doc);
            int okCount = 0;
            for (CompletableFuture<SectionResult> future : futures) {
                SectionResult result = future.join();
                mergedMap.put(result.type().name(),
                        result.status() == SectionResult.Status.OK ? "OK" : "FAILED");
                if (result.status() == SectionResult.Status.OK) {
                    contentByType.put(result.type(), result.content());
                }
                jobEventPublisher.publish(jobId, JobEvent.custom("section-complete", Map.of(
                        "type", result.type().name(),
                        "status", result.status() == SectionResult.Status.OK ? "OK" : "FAILED")));
            }
            for (DocType t : SECTIONS) {
                if ("OK".equals(mergedMap.get(t.name()))) okCount++;
            }
            doc.setSectionContents(serializeContentMap(contentByType));
            asyncJobService.updateProgress(jobId, 50, "Sections complete; rendering per-type PDFs");

            String projectName = aggregate.project() != null && aggregate.project().getName() != null
                    ? aggregate.project().getName() : projectId;
            renderPerTypePdfs(documentId, jobId, contentByType, projectName, typesToGenerate);

            asyncJobService.updateProgress(jobId, 85, "Stitching bundle PDF");
            String stitched = stitchMarkdown(projectId, aggregate, contentByType, mergedMap);
            MermaidPreRenderer.PreRenderResult pre = mermaidPreRenderer.preRender(stitched);
            String html = markdownPdfRenderer.markdownToHtml(pre.markdown());
            for (var entry : pre.placeholderToHtml().entrySet()) {
                html = html.replace(entry.getKey(), entry.getValue());
            }
            byte[] pdfBytes = markdownPdfRenderer.render(html, "Project Documentation: " + projectName);

            doc.setContentMd(stitched);
            doc.setContentPdf(pdfBytes);
            doc.setSectionResults(serializeSectionMap(mergedMap));
            doc.setGeneratedAt(OffsetDateTime.now());
            DocGenerationStatus terminal = okCount == SECTIONS.size()
                    ? DocGenerationStatus.COMPLETED : DocGenerationStatus.PARTIAL;
            doc.setStatus(terminal);
            doc.setErrorMessage(null);
            repository.save(doc);
            jobEventPublisher.publish(jobId, JobEvent.custom("bundle-ready", Map.of("documentId", documentId)));

            log.info("Full-doc generation finished project={} document={} status={}",
                    projectId, documentId, terminal);
            Map<String, Object> jobResult = Map.of(
                    "documentId", documentId,
                    "status", terminal,
                    "okCount", okCount,
                    "totalSections", SECTIONS.size());
            if (terminal == DocGenerationStatus.PARTIAL) {
                asyncJobService.markPartial(jobId, jobResult);
            } else {
                asyncJobService.markSucceeded(jobId, jobResult);
            }
        } catch (Exception e) {
            log.error("Full-doc generation failed for project={} document={}: {}",
                    projectId, documentId, e.getMessage(), e);
            repository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(DocGenerationStatus.FAILED);
                doc.setErrorMessage(e.getMessage());
                doc.setGeneratedAt(OffsetDateTime.now());
                repository.save(doc);
            });
            asyncJobService.markFailed(jobId, e.getMessage());
        }
    }

    private void renderPerTypePdfs(UUID documentId, UUID jobId, Map<DocType, String> contentByType,
                                    String projectName, List<DocType> typesGeneratedThisRun) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DocType type : typesGeneratedThisRun) {
            String md = contentByType.get(type);
            if (md == null || md.isBlank()) continue;
            futures.add(CompletableFuture.runAsync(() -> renderOneTypePdf(documentId, jobId, type, md, projectName),
                    brainLlmExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void renderOneTypePdf(UUID documentId, UUID jobId, DocType type, String md, String projectName) {
        try {
            MermaidPreRenderer.PreRenderResult pre = mermaidPreRenderer.preRender(md);
            String html = markdownPdfRenderer.markdownToHtml(pre.markdown());
            for (var entry : pre.placeholderToHtml().entrySet()) {
                html = html.replace(entry.getKey(), entry.getValue());
            }
            byte[] bytes = markdownPdfRenderer.render(html, projectName + ": " + sectionTitle(type));
            pdfRepository.deleteByDocumentIdAndDocType(documentId, type);
            GeneratedDocumentPdf row = new GeneratedDocumentPdf();
            row.setDocumentId(documentId);
            row.setDocType(type);
            row.setContentPdf(bytes);
            pdfRepository.save(row);
            jobEventPublisher.publish(jobId, JobEvent.custom("type-ready", Map.of(
                    "type", type.name(),
                    "sizeBytes", bytes.length)));
        } catch (Exception e) {
            log.warn("Per-type PDF render failed: document={} type={}: {}", documentId, type, e.getMessage());
            jobEventPublisher.publish(jobId, JobEvent.custom("type-failed", Map.of(
                    "type", type.name(),
                    "error", e.getMessage() == null ? "render error" : e.getMessage())));
        }
    }

    private static String sectionTitle(DocType type) {
        return switch (type) {
            case ARCHITECTURE      -> "Architecture";
            case SEQUENCE_DIAGRAM  -> "Sequence Flows";
            case CLASS_DIAGRAM     -> "Class Model";
            case FLOW_DIAGRAM      -> "Process Flows";
            case EXPLANATION       -> "Explanation";
            default                -> type.name();
        };
    }

    private SectionResult safeGenerateSection(String projectId, DocType type) {
        try {
            String content = docGeneratorService.generate(projectId, "", type);
            content = groundOrAnnotate(projectId, type, content);
            return SectionResult.ok(type, content);
        } catch (Exception e) {
            log.warn("Section {} failed for project={}: {}", type, projectId, e.getMessage());
            return SectionResult.failed(type, e.getMessage());
        }
    }

    private String groundOrAnnotate(String projectId, DocType type, String content) {
        com.assurant.brain.codegen.SymbolDictionary dict =
                symbolDictionaryBuilder.build(projectId);
        if (dict == null || dict == com.assurant.brain.codegen.SymbolDictionary.EMPTY) {
            return content;
        }

        var first = symbolGroundingValidator.validateMarkdown(content, dict);
        if (first.ok()) return content;

        log.info("Section {} for project={} has unknown class refs {} — retrying once with correction",
                type, projectId, first.issues());
        String correctionPrompt = "The previously-generated " + type.name()
                + " section referenced classes that do not exist in this project: "
                + first.issues()
                + ". Regenerate the section using ONLY classes that appear in the supplied code context "
                + "or in the GROUND TRUTH endpoint list. Do not invent new classes.";
        try {
            String retry = docGeneratorService.generate(projectId, correctionPrompt, type);
            var second = symbolGroundingValidator.validateMarkdown(retry, dict);
            if (second.ok()) {
                log.info("Section {} for project={} grounded after retry", type, projectId);
                return retry;
            }
            return retry + "\n\n> ⚠ Brain could not verify these references: "
                    + String.join(", ", second.issues())
                    + ". Treat them as illustrative.\n";
        } catch (Exception e) {
            log.warn("Symbol-grounding retry for {} failed: {}", type, e.getMessage());
            return content + "\n\n> ⚠ Brain could not verify these references: "
                    + String.join(", ", first.issues())
                    + ". Treat them as illustrative.\n";
        }
    }

    private Map<DocType, String> readExistingSectionContent(GeneratedDocument doc) {
        Map<DocType, String> existing = new LinkedHashMap<>();
        String json = doc.getSectionContents();
        if (json == null || json.isBlank()) return existing;
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<>() {});
            for (var entry : raw.entrySet()) {
                try {
                    existing.put(DocType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // unknown DocType from older row — skip
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse section contents: {}", e.getMessage());
        }
        return existing;
    }

    private String serializeContentMap(Map<DocType, String> map) {
        try {
            Map<String, String> raw = new LinkedHashMap<>();
            map.forEach((k, v) -> raw.put(k.name(), v));
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            log.debug("Failed to serialize section contents: {}", e.getMessage());
            return "{}";
        }
    }

    private String stitchMarkdown(String projectId, FullDocAggregate aggregate,
                                   Map<DocType, String> sections, Map<String, String> sectionStatus) {
        StringBuilder sb = new StringBuilder();
        appendCover(sb, projectId, aggregate, sectionStatus);
        appendOverview(sb, aggregate);
        appendSection(sb, "Architecture", sections.get(DocType.ARCHITECTURE), sectionStatus.get(DocType.ARCHITECTURE.name()));
        appendSection(sb, "Sequence Flows", sections.get(DocType.SEQUENCE_DIAGRAM), sectionStatus.get(DocType.SEQUENCE_DIAGRAM.name()));
        appendSection(sb, "Class Model", sections.get(DocType.CLASS_DIAGRAM), sectionStatus.get(DocType.CLASS_DIAGRAM.name()));
        appendSection(sb, "Process Flows", sections.get(DocType.FLOW_DIAGRAM), sectionStatus.get(DocType.FLOW_DIAGRAM.name()));
        appendSection(sb, "Explanation", sections.get(DocType.EXPLANATION), sectionStatus.get(DocType.EXPLANATION.name()));
        appendCommunitySummaries(sb, aggregate.communitySummaries());
        appendConventions(sb, aggregate.conventions());
        appendSlos(sb, aggregate.slos());
        appendIncidents(sb, aggregate.incidents());
        appendFlakyTests(sb, aggregate.flakyTests());
        appendRecentPullRequests(sb, aggregate.recentPullRequests());
        appendGenerationMetadata(sb, sectionStatus);
        return sb.toString();
    }

    private void appendCover(StringBuilder sb, String projectId, FullDocAggregate agg,
                              Map<String, String> sectionStatus) {
        ProjectNode p = agg.project();
        sb.append("# ").append(p == null ? projectId : (p.getName() == null ? projectId : p.getName()))
                .append('\n').append("Comprehensive Project Documentation\n\n");
        sb.append("| Project | ").append(projectId).append(" |\n");
        sb.append("|---|---|\n");
        if (p != null) {
            if (p.getLanguage() != null)  sb.append("| Language | ").append(p.getLanguage()).append(" |\n");
            if (p.getFramework() != null) sb.append("| Framework | ").append(p.getFramework()).append(" |\n");
            if (p.getKind() != null)      sb.append("| Kind | ").append(p.getKind()).append(" |\n");
        }
        sb.append("| Generated at | ").append(OffsetDateTime.now()).append(" |\n\n");
    }

    private void appendOverview(StringBuilder sb, FullDocAggregate agg) {
        sb.append("## Overview\n\n");
        ProjectNode p = agg.project();
        if (p == null) {
            sb.append("_No `ProjectNode` found in the graph._\n\n");
            return;
        }
        sb.append("- Modules: ").append(p.getModules() == null ? 0 : p.getModules().size()).append('\n');
        sb.append("- Conventions: ").append(agg.conventions().size()).append('\n');
        sb.append("- Incidents on record: ").append(agg.incidents().size()).append('\n');
        sb.append("- SLOs: ").append(agg.slos().size()).append('\n');
        sb.append("- Flaky tests tracked: ").append(agg.flakyTests().size()).append('\n');
        sb.append("- Community summaries: ").append(agg.communitySummaries().size()).append('\n').append('\n');
    }

    private void appendSection(StringBuilder sb, String heading, String content, String status) {
        sb.append("## ").append(heading).append('\n').append('\n');
        if (content == null || "FAILED".equals(status)) {
            sb.append("_Section unavailable (retry to regenerate)._\n\n");
        } else {
            sb.append(content).append("\n\n");
        }
    }

    private void appendCommunitySummaries(StringBuilder sb, List<CommunitySummaryNode> summaries) {
        if (summaries == null || summaries.isEmpty()) return;
        sb.append("## Module / Package Rollups (GraphRAG)\n\n");
        summaries.stream()
                .sorted(Comparator.comparingInt(CommunitySummaryNode::getLevel)
                        .thenComparing(CommunitySummaryNode::getCommunityKey))
                .forEach(s -> sb.append("**[L").append(s.getLevel()).append("] ")
                        .append(s.getCommunityKey()).append("** — ")
                        .append(s.getSummary() == null ? "(no summary)" : s.getSummary())
                        .append("\n\n"));
    }

    private void appendConventions(StringBuilder sb, List<ConventionNode> conventions) {
        if (conventions == null || conventions.isEmpty()) return;
        sb.append("## Conventions\n\n");
        sb.append("| Rule | Category | Source | Trust |\n|---|---|---|---|\n");
        conventions.stream().limit(50).forEach(c -> sb.append("| ")
                .append(escapeMd(c.getRule())).append(" | ")
                .append(c.getCategory() == null ? "" : c.getCategory()).append(" | ")
                .append(c.getSourceFile() == null ? "" : c.getSourceFile()).append(" | ")
                .append(c.getTrustWeight()).append(" |\n"));
        sb.append('\n');
    }

    private void appendSlos(StringBuilder sb, List<SLONode> slos) {
        if (slos == null || slos.isEmpty()) return;
        sb.append("## Service-Level Objectives\n\n");
        sb.append("| Service | Indicator | Target % | Window |\n|---|---|---|---|\n");
        slos.forEach(s -> sb.append("| ")
                .append(s.getServiceName() == null ? "" : s.getServiceName()).append(" | ")
                .append(s.getIndicatorType() == null ? "" : s.getIndicatorType()).append(" | ")
                .append(s.getTargetPercent()).append(" | ")
                .append(s.getWindow() == null ? "" : s.getWindow()).append(" |\n"));
        sb.append('\n');
    }

    private void appendIncidents(StringBuilder sb, List<IncidentNode> incidents) {
        if (incidents == null || incidents.isEmpty()) return;
        sb.append("## Known Incidents\n\n");
        incidents.stream().limit(20).forEach(i -> sb.append("- **[")
                .append(i.getSeverity() == null ? "INCIDENT" : i.getSeverity()).append(" / ")
                .append(i.getStatus() == null ? "?" : i.getStatus()).append(" / ")
                .append(i.getOccurredAt() == null ? "?" : i.getOccurredAt()).append("]** ")
                .append(i.getTitle()).append('\n')
                .append(i.getRootCauseSummary() == null ? "" : "  Root cause: " + i.getRootCauseSummary() + "\n"));
        sb.append('\n');
    }

    private void appendFlakyTests(StringBuilder sb, List<TestRunNode> flaky) {
        if (flaky == null || flaky.isEmpty()) return;
        sb.append("## Flaky Tests\n\n");
        sb.append("| Test FQN | Total runs | Failures | Flakiness |\n|---|---|---|---|\n");
        flaky.stream().limit(25).forEach(t -> sb.append("| ")
                .append(t.getTestFqn() == null ? "" : t.getTestFqn()).append(" | ")
                .append(t.getTotalRuns()).append(" | ")
                .append(t.getFailCount()).append(" | ")
                .append(String.format("%.2f", t.getFlakinessScore())).append(" |\n"));
        sb.append('\n');
    }

    private void appendRecentPullRequests(StringBuilder sb, List<PullRequestRecord> prs) {
        if (prs == null || prs.isEmpty()) return;
        sb.append("## Recent Pull Requests\n\n");
        sb.append("| Branch | Status | Created |\n|---|---|---|\n");
        prs.forEach(pr -> sb.append("| ")
                .append(pr.getBranchName() == null ? "" : pr.getBranchName()).append(" | ")
                .append(pr.getStatus() == null ? "" : pr.getStatus()).append(" | ")
                .append(pr.getCreatedAt() == null ? "" : pr.getCreatedAt()).append(" |\n"));
        sb.append('\n');
    }

    private void appendGenerationMetadata(StringBuilder sb, Map<String, String> sectionStatus) {
        sb.append("## Generation Metadata\n\n");
        sb.append("| Section | Status |\n|---|---|\n");
        SECTIONS.forEach(t -> sb.append("| ").append(t.name()).append(" | ")
                .append(sectionStatus.getOrDefault(t.name(), "PENDING")).append(" |\n"));
        sb.append('\n');
    }

    private String escapeMd(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String computeHash(FullDocAggregate aggregate) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ProjectNode p = aggregate.project();
            update(md, p == null ? "no-project" : (p.getId() + "|" + p.getName()
                    + "|" + p.getKind() + "|" + p.getLanguage() + "|" + p.getFramework()
                    + "|modules:" + (p.getModules() == null ? 0 : p.getModules().size())));
            aggregate.conventions().forEach(c -> update(md,
                    "|conv:" + c.getId() + "/" + c.getRule() + "/" + c.getTrustWeight()));
            aggregate.incidents().forEach(i -> update(md,
                    "|inc:" + i.getId() + "/" + i.getOccurredAt() + "/" + i.getStatus()));
            aggregate.slos().forEach(s -> update(md,
                    "|slo:" + s.getServiceName() + "/" + s.getIndicatorType() + "/" + s.getTargetPercent()));
            aggregate.flakyTests().forEach(t -> update(md,
                    "|flk:" + t.getTestFqn() + "/" + t.getTotalRuns() + "/" + t.getFailCount()));
            aggregate.communitySummaries().forEach(c -> update(md,
                    "|com:" + c.getCommunityKey() + "/" + c.getLevel()));
            aggregate.recentPullRequests().forEach(pr -> update(md,
                    "|pr:" + pr.getId() + "/" + pr.getStatus()));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return "no-hash";
        }
    }

    private void update(MessageDigest md, String s) {
        md.update(s == null ? new byte[0] : s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private long cacheTtlMinutes() {
        if (brainProperties.docs() == null || brainProperties.docs().cacheTtlMinutes() <= 0) return 60;
        return brainProperties.docs().cacheTtlMinutes();
    }

    private String initialSectionResults() {
        Map<String, String> map = new LinkedHashMap<>();
        SECTIONS.forEach(t -> map.put(t.name(), "PENDING"));
        return serializeSectionMap(map);
    }

    private String serializeSectionMap(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.debug("Failed to serialize section results: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, String> parseSectionMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse section results: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public FullDocStatusResponse toStatusResponse(GeneratedDocument doc) {
        OffsetDateTime horizon = doc.getGeneratedAt() == null
                ? null : doc.getGeneratedAt().plusMinutes(cacheTtlMinutes());
        List<DocType> available = pdfRepository.findByDocumentIdOrderByDocTypeAsc(doc.getId()).stream()
                .map(GeneratedDocumentPdf::getDocType).toList();
        boolean bundleReady = doc.getContentPdf() != null && doc.getContentPdf().length > 0;
        return new FullDocStatusResponse(
                doc.getId(),
                doc.getProjectId(),
                doc.getStatus(),
                parseSectionMap(doc.getSectionResults()),
                doc.getGeneratedAt(),
                horizon,
                false,
                doc.getErrorMessage(),
                bundleReady,
                available,
                null);
    }
}
