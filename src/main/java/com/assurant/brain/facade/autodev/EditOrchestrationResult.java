package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PlanGraph;

import java.util.List;
import java.util.Map;

public record EditOrchestrationResult(
        String projectId,
        PlanGraph planGraph,
        Map<String, String> files,
        List<String> nodeErrors
) {
    public boolean hasErrors() {
        return nodeErrors != null && !nodeErrors.isEmpty();
    }
}
