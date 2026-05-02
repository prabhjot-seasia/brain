package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.ChangeKind;
import com.assurant.brain.enums.SeamFlag;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SeamAnalyzer")
class SeamAnalyzerTest {

    private ProjectNodeRepository repo;
    private SeamAnalyzer analyzer;

    @BeforeEach
    void setup() {
        repo = mock(ProjectNodeRepository.class);
        BrainProperties.Autodev autodev = new BrainProperties.Autodev(0.7, 5, 50, 10, false);
        BrainProperties props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, autodev, null, null, null, null, null, null);
        analyzer = new SeamAnalyzer(repo, props);
    }

    @Test
    @DisplayName("empty graph returns empty graph")
    void emptyGraph() {
        assertThat(analyzer.annotate(PlanGraph.empty()).size()).isZero();
    }

    @Test
    @DisplayName("null graph returns empty graph")
    void nullGraph() {
        assertThat(analyzer.annotate(null).size()).isZero();
    }

    @Test
    @DisplayName("zero callers → SAFE_SEAM")
    void zeroCallersSafeSeam() {
        when(repo.findCallersByQualifiedName(anyString())).thenReturn(List.of());
        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(seed("com.example.Foo")), List.of()));
        assertThat(annotated.nodes().get(0).seamFlag()).isEqualTo(SeamFlag.SAFE_SEAM);
    }

    @Test
    @DisplayName("callers between 1 and threshold-1 → NEEDS_CHARACTERIZATION_TEST")
    void fewCallersNeedsTest() {
        when(repo.findCallersByQualifiedName(anyString()))
                .thenReturn(List.of(caller("A"), caller("B"), caller("C")));
        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(seed("com.example.Foo")), List.of()));
        assertThat(annotated.nodes().get(0).seamFlag()).isEqualTo(SeamFlag.NEEDS_CHARACTERIZATION_TEST);
    }

    @Test
    @DisplayName("callers at or above threshold → HIGH_BLAST_RADIUS")
    void manyCallersHighBlast() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) many.add(caller("C" + i));
        when(repo.findCallersByQualifiedName(anyString())).thenReturn(many);

        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(seed("com.example.Foo")), List.of()));
        assertThat(annotated.nodes().get(0).seamFlag()).isEqualTo(SeamFlag.HIGH_BLAST_RADIUS);
    }

    @Test
    @DisplayName("null targetSymbol is classified SAFE_SEAM without touching the repo")
    void nullTargetIsSafe() {
        PlanNode n = new PlanNode("x", "proj-a", "File.java", null,
                "change", ChangeKind.MODIFY_CLASS, false, null);
        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(n), List.of()));
        assertThat(annotated.nodes().get(0).seamFlag()).isEqualTo(SeamFlag.SAFE_SEAM);
    }

    @Test
    @DisplayName("repo failure → SAFE_SEAM fallback, never throws")
    void repoFailureFallsBack() {
        when(repo.findCallersByQualifiedName(anyString()))
                .thenThrow(new RuntimeException("neo4j down"));
        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(seed("com.example.Foo")), List.of()));
        assertThat(annotated.nodes().get(0).seamFlag()).isEqualTo(SeamFlag.SAFE_SEAM);
    }

    @Test
    @DisplayName("edges are preserved when graph is annotated")
    void edgesPreserved() {
        PlanNode a = seed("com.example.A");
        PlanNode b = seed("com.example.B");
        PlanEdge e = new PlanEdge(a.blockId(), b.blockId(), "PROPAGATES_TO");
        when(repo.findCallersByQualifiedName(anyString())).thenReturn(List.of());

        PlanGraph annotated = analyzer.annotate(new PlanGraph(List.of(a, b), List.of(e)));
        assertThat(annotated.edges()).containsExactly(e);
    }

    private PlanNode seed(String qualifiedName) {
        return new PlanNode("seed-" + qualifiedName, "proj-a", "F.java", qualifiedName,
                "change " + qualifiedName, ChangeKind.MODIFY_METHOD_SIGNATURE, false, null);
    }

    private Map<String, Object> caller(String fqn) {
        return Map.of("projectId", "proj-x", "qualifiedName", fqn, "filePath", fqn + ".java");
    }
}
