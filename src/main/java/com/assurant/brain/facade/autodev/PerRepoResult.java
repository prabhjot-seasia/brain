package com.assurant.brain.facade.autodev;

import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.enums.PerRepoStage;

import java.util.UUID;

public record PerRepoResult(
        String projectId,
        String repoUrl,
        PerRepoOutcome outcome,
        PerRepoStage failureStage,
        String prUrl,
        Integer prNumber,
        UUID prRecordId,
        String errorMessage
) {
    public static PerRepoResult success(String projectId, String repoUrl, String prUrl,
                                         Integer prNumber, UUID prRecordId) {
        return new PerRepoResult(projectId, repoUrl, PerRepoOutcome.SUCCESS, null,
                prUrl, prNumber, prRecordId, null);
    }

    public static PerRepoResult failed(String projectId, String repoUrl,
                                         PerRepoStage stage, String error, UUID prRecordId) {
        return new PerRepoResult(projectId, repoUrl, PerRepoOutcome.FAILED, stage,
                null, null, prRecordId, error);
    }

    public static PerRepoResult skipped(String projectId, String repoUrl, String reason) {
        return new PerRepoResult(projectId, repoUrl, PerRepoOutcome.SKIPPED, null,
                null, null, null, reason);
    }
}
