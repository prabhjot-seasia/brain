package com.assurant.brain.jobs;

import com.assurant.brain.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class AsyncJobService {

    private static final List<AsyncJobStatus> IN_FLIGHT = List.of(AsyncJobStatus.QUEUED, AsyncJobStatus.RUNNING);
    private static final int MAX_PROGRESS_MSG = 500;

    private final AsyncJobRepository repository;
    private final JobEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public AsyncJob startOrAttach(String jobType, String targetKind, String targetId, String projectId) {
        if (jobType == null || jobType.isBlank()
                || targetKind == null || targetKind.isBlank()
                || targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("jobType, targetKind, targetId are required");
        }
        Optional<AsyncJobEntity> existing = repository
                .findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(jobType, targetKind, targetId, IN_FLIGHT);
        if (existing.isPresent()) {
            log.info("AsyncJob attach jobType={} target={}/{} existing={}",
                    jobType, targetKind, targetId, existing.get().getId());
            return AsyncJob.fromEntity(existing.get(), true);
        }
        AsyncJobEntity entity = new AsyncJobEntity();
        entity.setJobType(jobType);
        entity.setTargetKind(targetKind);
        entity.setTargetId(targetId);
        entity.setProjectId(projectId);
        entity.setStatus(AsyncJobStatus.QUEUED);
        entity.setRequestedBy(SecurityUtils.currentUserId());
        try {
            AsyncJobEntity saved = repository.saveAndFlush(entity);
            log.info("AsyncJob queued id={} jobType={} target={}/{} project={}",
                    saved.getId(), jobType, targetKind, targetId, projectId);
            return AsyncJob.fromEntity(saved, false);
        } catch (DataIntegrityViolationException raceLost) {
            Optional<AsyncJobEntity> inFlight = repository
                    .findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(jobType, targetKind, targetId, IN_FLIGHT);
            if (inFlight.isPresent()) {
                log.info("AsyncJob race-lost; attached to in-flight {}", inFlight.get().getId());
                return AsyncJob.fromEntity(inFlight.get(), true);
            }
            entity.setId(null);
            try {
                AsyncJobEntity saved = repository.saveAndFlush(entity);
                log.info("AsyncJob race-resolved (peer terminated); queued id={}", saved.getId());
                return AsyncJob.fromEntity(saved, false);
            } catch (DataIntegrityViolationException stillRacing) {
                AsyncJobEntity now = repository
                        .findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(jobType, targetKind, targetId, IN_FLIGHT)
                        .orElseThrow(() -> new IllegalStateException(
                                "race-lost twice for " + jobType + "/" + targetKind + "/" + targetId, stillRacing));
                return AsyncJob.fromEntity(now, true);
            }
        }
    }

    @Transactional
    public AsyncJob markRunning(UUID jobId, String progressMsg) {
        AsyncJobEntity entity = mustFind(jobId);
        entity.setStatus(AsyncJobStatus.RUNNING);
        if (entity.getStartedAt() == null) entity.setStartedAt(OffsetDateTime.now());
        entity.setProgressMsg(truncate(progressMsg));
        AsyncJob job = AsyncJob.fromEntity(repository.saveAndFlush(entity));
        eventPublisher.publish(jobId, JobEvent.progress(job));
        return job;
    }

    @Transactional
    public AsyncJob updateProgress(UUID jobId, int pct, String progressMsg) {
        AsyncJobEntity entity = mustFind(jobId);
        if (entity.getStatus().isTerminal()) return AsyncJob.fromEntity(entity);
        entity.setProgressPct(clampPct(pct));
        entity.setProgressMsg(truncate(progressMsg));
        AsyncJob job = AsyncJob.fromEntity(repository.saveAndFlush(entity));
        eventPublisher.publish(jobId, JobEvent.progress(job));
        return job;
    }

    @Transactional
    public AsyncJob markSucceeded(UUID jobId, Object resultPayload) {
        return markTerminal(jobId, AsyncJobStatus.SUCCEEDED, resultPayload, null);
    }

    @Transactional
    public AsyncJob markPartial(UUID jobId, Object resultPayload) {
        return markTerminal(jobId, AsyncJobStatus.PARTIAL, resultPayload, null);
    }

    @Transactional
    public AsyncJob markFailed(UUID jobId, String errorMessage) {
        return markTerminal(jobId, AsyncJobStatus.FAILED, null, errorMessage);
    }

    @Transactional
    public AsyncJob cancel(UUID jobId) {
        return markTerminal(jobId, AsyncJobStatus.CANCELLED, null, null);
    }

    public Optional<AsyncJob> findById(UUID jobId) {
        return repository.findById(jobId).map(AsyncJob::fromEntity);
    }

    public List<AsyncJob> recentForProject(String projectId, int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId, PageRequest.of(0, safe))
                .stream().map(AsyncJob::fromEntity).toList();
    }

    public List<AsyncJob> recentInFlight(int limit) {
        int safe = Math.max(1, Math.min(limit, 100));
        return repository.findByStatusInOrderByCreatedAtDesc(IN_FLIGHT, PageRequest.of(0, safe))
                .stream().map(AsyncJob::fromEntity).toList();
    }

    public Optional<String> projectIdOf(UUID jobId) {
        return repository.findById(jobId).map(AsyncJobEntity::getProjectId);
    }

    private AsyncJob markTerminal(UUID jobId, AsyncJobStatus status, Object resultPayload, String errorMessage) {
        AsyncJobEntity entity = mustFind(jobId);
        entity.setStatus(status);
        entity.setFinishedAt(OffsetDateTime.now());
        if (errorMessage != null) entity.setErrorMessage(errorMessage);
        if (resultPayload != null) {
            try {
                entity.setResult(objectMapper.writeValueAsString(resultPayload));
            } catch (Exception e) {
                log.debug("Failed to serialize result for job={}: {}", jobId, e.getMessage());
            }
        }
        AsyncJob job = AsyncJob.fromEntity(repository.saveAndFlush(entity));
        eventPublisher.publish(jobId, JobEvent.terminal(job));
        eventPublisher.complete(jobId);
        return job;
    }

    private AsyncJobEntity mustFind(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("AsyncJob not found: " + jobId));
    }

    private static Short clampPct(int pct) {
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return (short) pct;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_PROGRESS_MSG ? s : s.substring(0, MAX_PROGRESS_MSG);
    }
}
