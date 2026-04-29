package com.assurant.brain.jobs.dto;

import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobStatus;

import java.util.UUID;

public record JobStartResponse(
        UUID jobId,
        String jobType,
        AsyncJobStatus status,
        boolean attachedToExisting,
        String projectId,
        String streamUrl,
        String pollUrl) {

    public static JobStartResponse from(AsyncJob job) {
        return new JobStartResponse(
                job.id(),
                job.jobType(),
                job.status(),
                job.attachedToExisting(),
                job.projectId(),
                "/api/v1/jobs/stream/" + job.id(),
                "/api/v1/jobs/" + job.id());
    }
}
