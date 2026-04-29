package com.assurant.brain.dto.response;

import com.assurant.brain.domain.PullRequestRecord;

import java.time.OffsetDateTime;
import java.util.Map;

public record PrRecordResponse(
        String id,
        String sessionId,
        String repoUrl,
        String baseBranch,
        String branchName,
        Integer prNumber,
        String prUrl,
        String status,
        Map<String, String> generatedFiles,
        int selfReviewIterations,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static PrRecordResponse from(PullRequestRecord record) {
        return new PrRecordResponse(
                record.getId().toString(),
                record.getSessionId().toString(),
                record.getRepoUrl(),
                record.getBaseBranch(),
                record.getBranchName(),
                record.getPrNumber(),
                record.getPrUrl(),
                record.getStatus().name(),
                record.getGeneratedFiles(),
                record.getSelfReviewIterations(),
                record.getErrorMessage(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
