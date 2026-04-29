package com.assurant.brain.dto.response;

import java.util.List;

public record TokenUsageSummary(
        long totalCalls,
        long cacheHits,
        double cacheHitRate,
        long totalInputTokens,
        long totalOutputTokens,
        double totalCost,
        List<ServiceBreakdown> breakdown
) {
    public record ServiceBreakdown(
            String serviceName,
            String operation,
            long inputTokens,
            long outputTokens,
            long callCount,
            double cost
    ) {}
}
