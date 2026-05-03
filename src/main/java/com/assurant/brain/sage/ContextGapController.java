package com.assurant.brain.sage;

import com.assurant.brain.sage.dto.ContextGapResponse;
import com.assurant.brain.sage.dto.ResolveGapRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/api/v1/projects/{projectId}/context-gaps")
@RequiredArgsConstructor
public class ContextGapController {

    private static final int DEFAULT_LIMIT = 50;

    private final SageInquisitor sageInquisitor;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<List<ContextGapResponse>> list(
            @PathVariable String projectId,
            @RequestParam(required = false) Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, 500));
        List<ContextGapResponse> body = sageInquisitor.openGaps(projectId, safeLimit).stream()
                .map(ContextGapResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@projectAccess.canAdminister(#projectId)")
    public ResponseEntity<?> resolve(@PathVariable String projectId,
                                      @Valid @RequestBody ResolveGapRequest body) {
        return sageInquisitor.resolveAnswer(projectId, body.gapSignature(),
                        body.answerKind(), body.answerValue())
                .map(g -> ResponseEntity.ok((Object) ContextGapResponse.from(g)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "context gap not found",
                        "projectId", projectId,
                        "gapSignature", body.gapSignature())));
    }

    @GetMapping(value = "/readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<SageInquisitor.ContextReadinessReport> readiness(@PathVariable String projectId) {
        return ResponseEntity.ok(sageInquisitor.readinessFor(projectId));
    }
}
