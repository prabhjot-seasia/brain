package com.assurant.brain.avenger.oracle;

import com.assurant.brain.graph.node.SLONode;
import com.assurant.brain.graph.repository.SLONodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class OraclePromptOptimizer {

    private static final double HIGH_CRITICALITY_THRESHOLD = 99.9;
    private static final double MEDIUM_CRITICALITY_THRESHOLD = 99.5;

    private static final int AVG_DOC_TOKENS = 250;
    private static final int GROUNDING_OVERHEAD_TOKENS = 100;

    private static final RetrievalBudget HIGH_BUDGET    = build(200, 20, "HIGH");
    private static final RetrievalBudget MEDIUM_BUDGET  = build(100, 10, "MEDIUM");
    private static final RetrievalBudget LOW_BUDGET     = build(50, 5, "LOW");
    private static final RetrievalBudget DEFAULT_BUDGET = build(100, 10, "DEFAULT");

    private final SLONodeRepository sloNodeRepository;

    public record RetrievalBudget(int recallK, int rerankK, String tier,
                                   int recallTokenCost, int rerankTokenCost, int groundingTokenCost) {}

    private static RetrievalBudget build(int recallK, int rerankK, String tier) {
        return new RetrievalBudget(recallK, rerankK, tier,
                recallK * AVG_DOC_TOKENS,
                rerankK * AVG_DOC_TOKENS,
                GROUNDING_OVERHEAD_TOKENS);
    }

    public RetrievalBudget computeBudget(String projectId) {
        if (projectId == null || projectId.isBlank()) return DEFAULT_BUDGET;
        try {
            List<SLONode> slos = sloNodeRepository.findByProjectIdOrderByTargetDesc(projectId);
            if (slos.isEmpty()) return DEFAULT_BUDGET;
            double topTarget = slos.get(0).getTargetPercent();
            RetrievalBudget budget = pickBudget(topTarget);
            log.debug("ORACLE budget for project={} target={}: {}", projectId, topTarget, budget);
            return budget;
        } catch (RuntimeException e) {
            log.debug("ORACLE budget lookup failed for project={}: {}", projectId, e.getMessage());
            return DEFAULT_BUDGET;
        }
    }

    private RetrievalBudget pickBudget(double targetPercent) {
        if (targetPercent >= HIGH_CRITICALITY_THRESHOLD) return HIGH_BUDGET;
        if (targetPercent >= MEDIUM_CRITICALITY_THRESHOLD) return MEDIUM_BUDGET;
        return LOW_BUDGET;
    }
}
