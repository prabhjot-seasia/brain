package com.assurant.brain.learning;

import com.assurant.brain.enums.AvengerType;

import java.util.List;
import java.util.Map;

public record AvengerMemorySnapshot(
        AvengerType avenger,
        String projectId,
        int totalEvents,
        Map<String, Integer> violationCounts,
        List<String> topViolations,
        List<String> recentIssues,
        String trendHint
) {
    public static AvengerMemorySnapshot empty(AvengerType avenger, String projectId) {
        return new AvengerMemorySnapshot(avenger, projectId, 0, Map.of(), List.of(), List.of(), "");
    }

    public boolean isEmpty() {
        return totalEvents == 0;
    }
}
