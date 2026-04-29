package com.assurant.brain.docs.dto;

import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FullDocStatusResponse(
        UUID id,
        String projectId,
        DocGenerationStatus status,
        Map<String, String> sectionResults,
        OffsetDateTime generatedAt,
        OffsetDateTime cacheHorizon,
        boolean fromCache,
        String error,
        boolean bundlePdfReady,
        List<DocType> availablePdfTypes,
        UUID jobId
) {}
