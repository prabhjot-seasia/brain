package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.ChangeKind;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class DependencyPropagationAnalyzer {

    private static final int DEFAULT_MAX_PLAN_NODES = 50;

    private final ProjectNodeRepository projectNodeRepository;
    private final BrainProperties       brainProperties;

    public PlanGraph propagate(List<PlanNode> seedEdits) {
        if (seedEdits == null || seedEdits.isEmpty()) {
            return PlanGraph.empty();
        }

        int cap = resolveMaxPlanNodes();
        List<PlanNode> nodes = new ArrayList<>();
        List<PlanEdge> edges = new ArrayList<>();
        Set<String> knownIds = new HashSet<>();

        for (PlanNode seed : seedEdits) {
            if (nodes.size() >= cap) break;
            nodes.add(seed);
            knownIds.add(seed.blockId());
        }

        int derivedIdx = 0;
        for (PlanNode seed : seedEdits) {
            if (nodes.size() >= cap) break;
            if (!triggersPropagation(seed.kind())) continue;
            if (seed.targetSymbol() == null || seed.targetSymbol().isBlank()) continue;

            List<Map<String, Object>> callers;
            try {
                callers = projectNodeRepository.findCallersByQualifiedName(seed.targetSymbol());
            } catch (Exception e) {
                log.warn("Caller lookup failed for symbol={} — skipping propagation for this seed. Cause: {}",
                        seed.targetSymbol(), e.getMessage());
                continue;
            }

            for (Map<String, Object> caller : callers) {
                if (nodes.size() >= cap) {
                    log.warn("Plan graph hit max-plan-nodes cap={} — truncating further propagation", cap);
                    return new PlanGraph(nodes, edges);
                }
                String callerProject = asString(caller.get("projectId"));
                String callerFqn     = asString(caller.get("qualifiedName"));
                String callerFile    = asString(caller.get("filePath"));
                if (callerFqn == null || callerFqn.isBlank()) continue;
                if (callerFqn.equalsIgnoreCase(seed.targetSymbol())) continue;

                String derivedId = "derived-" + (derivedIdx++) + "-" + callerFqn.hashCode();
                if (!knownIds.add(derivedId)) continue;

                PlanNode derived = new PlanNode(
                        derivedId,
                        callerProject != null ? callerProject : seed.projectId(),
                        callerFile,
                        callerFqn,
                        "Update call site in " + callerFqn + " after " + describeChange(seed),
                        inferDerivedChangeKind(seed.kind()),
                        true,
                        null
                );
                nodes.add(derived);
                edges.add(new PlanEdge(seed.blockId(), derivedId, "PROPAGATES_TO"));
            }
        }

        log.info("Dependency propagation: {} seed + {} derived = {} total plan node(s)",
                seedEdits.size(), nodes.size() - seedEdits.size(), nodes.size());
        return new PlanGraph(nodes, edges);
    }

    static boolean triggersPropagation(ChangeKind kind) {
        if (kind == null) return false;
        return switch (kind) {
            case MODIFY_METHOD_SIGNATURE, MODIFY_CLASS, DELETE_METHOD, DELETE_CLASS -> true;
            default -> false;
        };
    }

    static ChangeKind inferDerivedChangeKind(ChangeKind seed) {
        if (seed == null) return ChangeKind.UNKNOWN;
        return switch (seed) {
            case MODIFY_METHOD_SIGNATURE, MODIFY_CLASS -> ChangeKind.MODIFY_METHOD_BODY;
            case DELETE_METHOD, DELETE_CLASS          -> ChangeKind.MODIFY_METHOD_BODY;
            default -> ChangeKind.UNKNOWN;
        };
    }

    private int resolveMaxPlanNodes() {
        BrainProperties.Autodev autodev = brainProperties != null ? brainProperties.autodev() : null;
        return autodev != null && autodev.maxPlanNodes() > 0
                ? autodev.maxPlanNodes()
                : DEFAULT_MAX_PLAN_NODES;
    }

    private static String describeChange(PlanNode node) {
        return node.targetSymbol() + " was modified (" + node.kind() + ")";
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
