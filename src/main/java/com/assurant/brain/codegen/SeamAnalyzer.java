package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.SeamFlag;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class SeamAnalyzer {

    private static final int DEFAULT_BLAST_RADIUS_THRESHOLD = 10;

    private final ProjectNodeRepository projectNodeRepository;
    private final BrainProperties       brainProperties;

    public PlanGraph annotate(PlanGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) return PlanGraph.empty();

        int threshold = resolveBlastRadiusThreshold();
        List<PlanNode> annotated = new ArrayList<>(graph.nodes().size());

        for (PlanNode node : graph.nodes()) {
            SeamFlag flag = classifySeam(node, threshold);
            annotated.add(node.withSeamFlag(flag));
        }

        log.info("SeamAnalyzer annotated {} node(s) (blast-radius threshold={})",
                annotated.size(), threshold);
        return new PlanGraph(annotated, graph.edges());
    }

    private SeamFlag classifySeam(PlanNode node, int blastRadiusThreshold) {
        if (node.targetSymbol() == null || node.targetSymbol().isBlank()) {
            return SeamFlag.SAFE_SEAM;
        }

        int callerCount = findCallersOf(node.targetSymbol()).size();

        if (callerCount >= blastRadiusThreshold) return SeamFlag.HIGH_BLAST_RADIUS;
        if (callerCount >= 1)                    return SeamFlag.NEEDS_CHARACTERIZATION_TEST;
        return SeamFlag.SAFE_SEAM;
    }

    public record CallerSite(String projectId, String qualifiedName, String filePath) {}

    public List<CallerSite> findCallersOf(String symbolFqn) {
        if (symbolFqn == null || symbolFqn.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows = projectNodeRepository.findCallersByQualifiedName(symbolFqn);
            if (rows == null) return List.of();
            List<CallerSite> out = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                out.add(new CallerSite(
                        stringOf(row.get("projectId")),
                        stringOf(row.get("qualifiedName")),
                        stringOf(row.get("filePath"))));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Caller lookup failed for symbol={}: {}", symbolFqn, e.getMessage());
            return List.of();
        }
    }

    private String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    private int resolveBlastRadiusThreshold() {
        BrainProperties.Autodev autodev = brainProperties != null ? brainProperties.autodev() : null;
        return autodev != null && autodev.blastRadiusThreshold() > 0
                ? autodev.blastRadiusThreshold()
                : DEFAULT_BLAST_RADIUS_THRESHOLD;
    }
}
