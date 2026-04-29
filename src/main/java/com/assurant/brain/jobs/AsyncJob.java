package com.assurant.brain.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AsyncJob(
        UUID id,
        String jobType,
        String targetKind,
        String targetId,
        String projectId,
        AsyncJobStatus status,
        String requestedBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Short progressPct,
        String progressMsg,
        String result,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean attachedToExisting) {

    public static AsyncJob fromEntity(AsyncJobEntity e, boolean attachedToExisting) {
        return new AsyncJob(
                e.getId(),
                e.getJobType(),
                e.getTargetKind(),
                e.getTargetId(),
                e.getProjectId(),
                e.getStatus(),
                e.getRequestedBy(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.getProgressPct(),
                e.getProgressMsg(),
                e.getResult(),
                e.getErrorMessage(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                attachedToExisting);
    }

    public static AsyncJob fromEntity(AsyncJobEntity e) {
        return fromEntity(e, false);
    }
}
