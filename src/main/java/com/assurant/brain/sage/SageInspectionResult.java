package com.assurant.brain.sage;

import com.assurant.brain.sage.domain.ContextGapResolution;

import java.util.List;

public record SageInspectionResult(
        List<ContextGapResolution> gapsCreated,
        List<ContextGapResolution> gapsAutoResolved,
        int tier1Unresolved,
        int tier2Unresolved,
        int tier3Unresolved,
        List<String> inlineAnnotations) {

    public static SageInspectionResult empty() {
        return new SageInspectionResult(List.of(), List.of(), 0, 0, 0, List.of());
    }
}
