package com.assurant.brain.testing;

import com.assurant.brain.graph.node.BddScenarioNode;
import com.assurant.brain.graph.repository.BddScenarioNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class BddCoverageService {

    private static final double DEFAULT_SIMILARITY = 0.45;

    private final BddScenarioNodeRepository scenarioRepository;

    public BddCoverageReport reportFor(String coversProjectId, List<TestScenario> targetScenarios) {
        if (coversProjectId == null || coversProjectId.isBlank() || targetScenarios == null) {
            return BddCoverageReport.empty();
        }
        List<BddScenarioNode> indexed;
        try {
            indexed = scenarioRepository.findByCoversProjectId(coversProjectId);
        } catch (RuntimeException e) {
            log.debug("BddCoverageService: lookup failed for project={}: {}", coversProjectId, e.getMessage());
            return BddCoverageReport.empty();
        }

        List<BddCoverageReport.ScenarioMatch> matches = new ArrayList<>();
        List<TestScenario> uncovered = new ArrayList<>();

        for (TestScenario target : targetScenarios) {
            if (target.type() != TestScenarioType.AUTOMATED) {
                uncovered.add(target);
                continue;
            }
            BddCoverageReport.ScenarioMatch best = bestMatch(target, indexed);
            if (best != null && best.similarity() >= DEFAULT_SIMILARITY) {
                matches.add(best);
            } else {
                uncovered.add(target);
            }
        }
        return new BddCoverageReport(indexed.size(), matches.size(), uncovered.size(), matches, uncovered);
    }

    private BddCoverageReport.ScenarioMatch bestMatch(TestScenario target, List<BddScenarioNode> indexed) {
        Set<String> targetTokens = tokenize(target.description());
        if (targetTokens.isEmpty()) return null;

        BddScenarioNode bestNode = null;
        double bestScore = 0;
        for (BddScenarioNode node : indexed) {
            double score = jaccard(targetTokens, tokenize(node.getScenarioTitle()));
            if (score > bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }
        if (bestNode == null || bestScore == 0) return null;
        return new BddCoverageReport.ScenarioMatch(target, bestNode, bestScore);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3) tokens.add(t);
        }
        return tokens;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
