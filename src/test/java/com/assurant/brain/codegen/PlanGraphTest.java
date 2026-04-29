package com.assurant.brain.codegen;

import com.assurant.brain.enums.ChangeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanGraph")
class PlanGraphTest {

    @Test
    @DisplayName("empty() returns graph with no nodes or edges")
    void emptyGraph() {
        PlanGraph g = PlanGraph.empty();
        assertThat(g.size()).isZero();
        assertThat(g.nodes()).isEmpty();
        assertThat(g.edges()).isEmpty();
    }

    @Test
    @DisplayName("seedNodes and derivedNodes partition correctly")
    void partitioning() {
        PlanNode seed = node("seed-1", false);
        PlanNode derived = node("derived-1", true);
        PlanGraph g = new PlanGraph(List.of(seed, derived), List.of());

        assertThat(g.seedNodes()).containsExactly(seed);
        assertThat(g.derivedNodes()).containsExactly(derived);
    }

    @Test
    @DisplayName("topologicalOrder places seed before its derived edits")
    void topologicalOrderRespectsEdges() {
        PlanNode seed = node("seed-1", false);
        PlanNode derived = node("derived-1", true);
        PlanGraph g = new PlanGraph(
                List.of(derived, seed),
                List.of(new PlanEdge("seed-1", "derived-1", "PROPAGATES_TO")));

        List<PlanNode> order = g.topologicalOrder();
        assertThat(order.indexOf(seed)).isLessThan(order.indexOf(derived));
    }

    @Test
    @DisplayName("topologicalOrder tolerates cycles by falling back to insertion order")
    void tolerateCycle() {
        PlanNode a = node("a", false);
        PlanNode b = node("b", false);
        PlanGraph g = new PlanGraph(
                List.of(a, b),
                List.of(new PlanEdge("a", "b", "PROPAGATES_TO"),
                        new PlanEdge("b", "a", "PROPAGATES_TO")));

        List<PlanNode> order = g.topologicalOrder();
        assertThat(order).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("PlanNode.withSeamFlag preserves other fields")
    void withSeamFlagPreserves() {
        PlanNode n = node("x", false);
        PlanNode flagged = n.withSeamFlag(com.assurant.brain.enums.SeamFlag.HIGH_BLAST_RADIUS);
        assertThat(flagged.blockId()).isEqualTo(n.blockId());
        assertThat(flagged.kind()).isEqualTo(n.kind());
        assertThat(flagged.seamFlag()).isEqualTo(com.assurant.brain.enums.SeamFlag.HIGH_BLAST_RADIUS);
    }

    private PlanNode node(String id, boolean derived) {
        return new PlanNode(id, "proj-1", "src/File.java", "com.example.Foo",
                "change " + id, ChangeKind.MODIFY_METHOD_SIGNATURE, derived, null);
    }
}
