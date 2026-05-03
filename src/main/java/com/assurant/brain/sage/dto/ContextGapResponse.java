package com.assurant.brain.sage.dto;

import com.assurant.brain.sage.AnswerSource;
import com.assurant.brain.sage.ContextGapType;
import com.assurant.brain.sage.GapStatus;
import com.assurant.brain.sage.domain.ContextGapResolution;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ContextGapResponse(
        UUID id,
        String projectId,
        String gapSignature,
        ContextGapType gapType,
        int tier,
        GapStatus status,
        String question,
        String suggestedAnswer,
        List<String> suggestedChoices,
        String answerKind,
        String answerValue,
        AnswerSource answerSource,
        BigDecimal confidence,
        String linkedIssueKey,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ContextGapResponse from(ContextGapResolution g) {
        return new ContextGapResponse(
                g.getId(), g.getProjectId(), g.getGapSignature(), g.getGapType(),
                g.getTier(), g.getStatus(), g.getQuestion(), g.getSuggestedAnswer(),
                g.getSuggestedChoices(), g.getAnswerKind(), g.getAnswerValue(),
                g.getAnswerSource(), g.getConfidence(), g.getLinkedIssueKey(),
                g.getCreatedAt(), g.getUpdatedAt());
    }
}
