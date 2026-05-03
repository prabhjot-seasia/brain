package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.docs.dto.ErrorResponse;
import com.assurant.brain.docs.dto.FullDocHistoryRow;
import com.assurant.brain.docs.dto.FullDocStartResponse;
import com.assurant.brain.docs.dto.FullDocStatusResponse;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocGeneratorController {

    private final DocGeneratorService docGeneratorService;
    private final GeneratedDocumentRepository documentRepository;
    private final FullDocBundleService fullDocBundleService;
    private final FullDocRateLimiter fullDocRateLimiter;
    private final BrainProperties brainProperties;
    private final com.assurant.brain.confluence.ConfluencePublisherService confluencePublisherService;

    private static final int DEFAULT_MAX_PDF_BYTES = 50 * 1024 * 1024;

    private int maxPdfBytes() {
        if (brainProperties.docs() == null || brainProperties.docs().maxPdfBytes() <= 0) {
            return DEFAULT_MAX_PDF_BYTES;
        }
        return brainProperties.docs().maxPdfBytes();
    }

    @PostMapping("/generate")
    @PreAuthorize("@projectAccess.canWrite(#body['projectId'])")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        String prompt = body.get("prompt");
        String typeStr = body.getOrDefault("type", "EXPLANATION");

        if (projectId == null || projectId.isBlank() || prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectId and prompt are required"));
        }

        DocType docType;
        try {
            docType = DocType.valueOf(typeStr.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid doc type: " + typeStr,
                    "validTypes", java.util.Arrays.stream(DocType.values()).map(Enum::name).toList()
            ));
        }

        String title = prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt;

        GeneratedDocument doc = new GeneratedDocument();
        doc.setProjectId(projectId);
        doc.setTitle(title);
        doc.setDocType(docType);
        doc.setPrompt(prompt);
        doc.setStatus(DocGenerationStatus.PENDING);
        documentRepository.save(doc);

        docGeneratorService.generateAsync(doc.getId());

        log.info("Doc generation queued: id={} project={} type={}", doc.getId(), projectId, docType);

        return ResponseEntity.accepted().body(Map.of(
                "id", doc.getId().toString(),
                "status", DocGenerationStatus.PENDING.name(),
                "title", title,
                "docType", docType.name()
        ));
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("@projectAccess.canReadDocument(#id)")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable UUID id) {
        return documentRepository.findById(id)
                .map(doc -> {
                    Map<String, Object> result = new java.util.HashMap<>(Map.of(
                            "id", doc.getId().toString(),
                            "status", doc.getStatus().name(),
                            "docType", doc.getDocType().name(),
                            "title", doc.getTitle()
                    ));
                    if (doc.getStatus() == DocGenerationStatus.COMPLETED && doc.getContentMd() != null) {
                        result.put("contentMd", doc.getContentMd());
                    }
                    if (doc.getStatus() == DocGenerationStatus.FAILED && doc.getErrorMessage() != null) {
                        result.put("error", doc.getErrorMessage());
                    }
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<List<GeneratedDocument>> listByProject(@RequestParam String projectId) {
        return ResponseEntity.ok(documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@projectAccess.canReadDocument(#id)")
    public ResponseEntity<GeneratedDocument> getById(@PathVariable UUID id) {
        return documentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@projectAccess.canWriteDocument(#id)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/full/{projectId}")
    @PreAuthorize("@projectAccess.canWrite(#projectId)")
    public ResponseEntity<?> generateFull(@PathVariable String projectId) {
        try {
            fullDocRateLimiter.check("full:" + projectId);
        } catch (FullDocRateLimiter.RateLimitExceededException e) {
            return ResponseEntity.status(429).body(new ErrorResponse(e.getMessage()));
        }
        FullDocBundleService.GenerateResult result = fullDocBundleService.startGeneration(projectId);
        FullDocStartResponse body = toStartResponse(result);
        return result.fromCache()
                ? ResponseEntity.ok(body)
                : ResponseEntity.accepted().body(body);
    }

    @PostMapping("/{id}/retry-failed")
    @PreAuthorize("@projectAccess.canWriteDocument(#id)")
    public ResponseEntity<?> retryFailed(@PathVariable UUID id) {
        try {
            fullDocRateLimiter.check("retry:" + id);
        } catch (FullDocRateLimiter.RateLimitExceededException e) {
            return ResponseEntity.status(429).body(new ErrorResponse(e.getMessage()));
        }
        FullDocBundleService.GenerateResult result = fullDocBundleService.retryFailedSections(id);
        return ResponseEntity.accepted().body(toStartResponse(result));
    }

    private FullDocStartResponse toStartResponse(FullDocBundleService.GenerateResult result) {
        String streamUrl = result.jobId() == null ? null : "/api/v1/jobs/stream/" + result.jobId();
        return new FullDocStartResponse(
                result.documentId(), result.status(), result.fromCache(), result.generatedAt(),
                result.jobId(), result.attachedToExisting(), streamUrl);
    }

    @GetMapping("/full/status/{id}")
    @PreAuthorize("@projectAccess.canReadDocument(#id)")
    public ResponseEntity<FullDocStatusResponse> fullStatus(@PathVariable UUID id) {
        return fullDocBundleService.status(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/publish/confluence")
    @PreAuthorize("@projectAccess.canWriteDocument(#id)")
    public ResponseEntity<?> publishToConfluence(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody com.assurant.brain.confluence.dto.ConfluenceTarget target) {
        if (brainProperties.confluence() == null || !brainProperties.confluence().enabled()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Confluence integration is not enabled (brain.confluence.enabled=false)"));
        }
        return documentRepository.findById(id)
                .map(doc -> {
                    com.assurant.brain.confluence.dto.PublishResult result =
                            confluencePublisherService.publishOrUpdate(doc, target);
                    int httpStatus = switch (result.status()) {
                        case CREATED, UPDATED -> 200;
                        case PARTIAL -> 207;
                        case SKIPPED -> 409;
                        case FAILED -> 502;
                    };
                    return ResponseEntity.status(httpStatus).body(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/pdf/{docType}")
    @PreAuthorize("@projectAccess.canReadDocument(#id)")
    public ResponseEntity<byte[]> typedPdf(@PathVariable UUID id, @PathVariable DocType docType) {
        return fullDocBundleService.downloadTypedPdf(id, docType)
                .map(bytes -> {
                    if (bytes.length > maxPdfBytes()) {
                        log.warn("Refusing to ship oversized typed PDF: doc={} type={} size={}", id, docType, bytes.length);
                        return ResponseEntity.status(413).<byte[]>build();
                    }
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + id + "-" + docType.name().toLowerCase() + ".pdf\"")
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                            .header("X-Content-Type-Options", "nosniff")
                            .header("Content-Security-Policy", "default-src 'none'; sandbox")
                            .header("Referrer-Policy", "no-referrer")
                            .contentType(MediaType.APPLICATION_PDF)
                            .body(bytes);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@projectAccess.canReadDocument(#id)")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return fullDocBundleService.downloadPdf(id)
                .map(bytes -> {
                    if (bytes.length > maxPdfBytes()) {
                        log.warn("Refusing to ship oversized PDF: id={} size={}", id, bytes.length);
                        return ResponseEntity.status(413).<byte[]>build();
                    }
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"project-doc-" + id + ".pdf\"")
                            .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                            .header("X-Content-Type-Options", "nosniff")
                            .header("Content-Security-Policy", "default-src 'none'; sandbox")
                            .header("Referrer-Policy", "no-referrer")
                            .contentType(MediaType.APPLICATION_PDF)
                            .body(bytes);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/full")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<List<FullDocHistoryRow>> listFullDocsByProject(@RequestParam String projectId) {
        var rows = fullDocBundleService.historyForProject(projectId).stream()
                .map(d -> new FullDocHistoryRow(
                        d.getId(),
                        d.getStatus(),
                        d.getGeneratedAt(),
                        d.getCreatedAt(),
                        d.getContentPdf() == null ? 0 : d.getContentPdf().length))
                .toList();
        return ResponseEntity.ok(rows);
    }
}
