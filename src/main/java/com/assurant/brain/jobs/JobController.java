package com.assurant.brain.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Log4j2
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final AsyncJobService asyncJobService;
    private final JobEventPublisher eventPublisher;

    @GetMapping("/{jobId}")
    @PreAuthorize("@projectAccess.canReadJob(#jobId)")
    public ResponseEntity<AsyncJob> getJob(@PathVariable UUID jobId) {
        return asyncJobService.findById(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@projectAccess.canReadJob(#jobId)")
    public SseEmitter streamJob(@PathVariable UUID jobId) {
        SseEmitter emitter = eventPublisher.subscribe(jobId);
        asyncJobService.findById(jobId).ifPresentOrElse(
                job -> sendSnapshot(emitter, jobId, job),
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("not-found").data(jobId));
                    } catch (IOException ignored) {
                        // client disconnected
                    } finally {
                        emitter.complete();
                    }
                });
        return emitter;
    }

    @GetMapping
    @PreAuthorize("#projectId == null or @projectAccess.canRead(#projectId)")
    public ResponseEntity<List<AsyncJob>> listJobs(
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "20") int limit) {
        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.ok(asyncJobService.recentInFlight(limit));
        }
        return ResponseEntity.ok(asyncJobService.recentForProject(projectId, limit));
    }

    private void sendSnapshot(SseEmitter emitter, UUID jobId, AsyncJob job) {
        try {
            emitter.send(SseEmitter.event().name("status").data(JobEvent.snapshot(job).data()));
            if (job.status().isTerminal()) {
                emitter.send(SseEmitter.event().name(JobEvent.terminal(job).name()).data(JobEvent.terminal(job).data()));
                emitter.complete();
            }
        } catch (IOException e) {
            log.debug("SSE snapshot send failed for job={}: {}", jobId, e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
