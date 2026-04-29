package com.assurant.brain.avenger.dto;

import com.assurant.brain.enums.AvengerVerdict;

import java.util.List;

public record FullReviewResponse(
        AvengerVerdict overallVerdict,
        int totalAvengers,
        int approved,
        int changesRequested,
        int blocked,
        long totalLatencyMs,
        List<AvengerResponse> reviews
) {}
