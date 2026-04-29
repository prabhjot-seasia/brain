package com.assurant.brain.docs.dto;

import com.assurant.brain.enums.DocGenerationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FullDocStartResponse(
        UUID documentId,
        DocGenerationStatus status,
        boolean fromCache,
        OffsetDateTime generatedAt,
        UUID jobId,
        boolean attachedToExisting,
        String streamUrl
) {

    public FullDocStartResponse(UUID documentId, DocGenerationStatus status, boolean fromCache,
                                 OffsetDateTime generatedAt) {
        this(documentId, status, fromCache, generatedAt, null, false, null);
    }
}
