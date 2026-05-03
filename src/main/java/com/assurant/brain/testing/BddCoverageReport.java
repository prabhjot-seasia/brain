package com.assurant.brain.testing;

import com.assurant.brain.graph.node.BddScenarioNode;

import java.util.List;

public record BddCoverageReport(
        int indexedScenarios,
        int coveredCount,
        int gapCount,
        List<ScenarioMatch> matches,
        List<TestScenario> uncoveredScenarios) {

    public record ScenarioMatch(TestScenario target, BddScenarioNode existing, double similarity) {}

    public static BddCoverageReport empty() {
        return new BddCoverageReport(0, 0, 0, List.of(), List.of());
    }
}
