package com.assurant.brain.docs.dto;

import com.assurant.brain.enums.DocGenerationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FullDocHistoryRow(
        UUID id,
        DocGenerationStatus status,
        OffsetDateTime generatedAt,
        OffsetDateTime createdAt,
        long sizeBytes
) {}
