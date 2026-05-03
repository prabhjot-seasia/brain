package com.assurant.brain.rest.v1.analyze.controller;

import com.assurant.brain.dto.request.AnalyzeRequest;
import com.assurant.brain.dto.response.AnalyzeResponse;
import com.assurant.brain.facade.AnalysisFacade;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.dto.JobStartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Log4j2
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class AnalyzeController {

    private final AnalysisFacade analysisFacade;
    private final AsyncJobService asyncJobService;
    private final com.assurant.brain.sage.SageInquisitor sageInquisitor;

    @PostMapping(value = "/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        log.info("Analyze request for project={}", request.projectId());
        sageInspect(request);
        return ResponseEntity.ok(analysisFacade.analyze(request));
    }

    private void sageInspect(AnalyzeRequest request) {
        try {
            if (request.projectId() == null || request.projectId().isBlank()) return;
            sageInquisitor.inspect(new com.assurant.brain.sage.BrainInputEvent(
                    com.assurant.brain.sage.BrainInputEventType.REQUIREMENT_ANALYSIS,
                    request.projectId(), null, request.requirement(),
                    java.util.List.of(), java.util.Map.of()));
        } catch (RuntimeException e) {
            log.debug("SAGE inspection skipped for analyze: {}", e.getMessage());
        }
    }

    @PostMapping(value = "/analyze/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobStartResponse> analyzeAsync(@Valid @RequestBody AnalyzeRequest request) {
        String targetId = request.sessionId() != null
                ? request.sessionId()
                : "new:" + UUID.randomUUID();
        AsyncJob job = asyncJobService.startOrAttach(
                "ANALYZE_REQUIREMENT", "SESSION", targetId, request.projectId());
        if (!job.attachedToExisting()) {
            analysisFacade.analyzeAsync(request, job.id());
        }
        log.info("Analyze queued: session={} job={}", targetId, job.id());
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }
}
