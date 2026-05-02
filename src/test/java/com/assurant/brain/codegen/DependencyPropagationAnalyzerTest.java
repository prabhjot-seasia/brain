package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.ChangeKind;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DependencyPropagationAnalyzer")
class DependencyPropagationAnalyzerTest {

    private ProjectNodeRepository repo;
    private DependencyPropagationAnalyzer analyzer;

    @BeforeEach
    void setup() {
        repo = mock(ProjectNodeRepository.class);
        BrainProperties.Autodev autodev = new BrainProperties.Autodev(0.7, 5, 50, 10, false);
        BrainProperties props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, autodev, null, null, null, null, null, null);
        analyzer = new DependencyPropagationAnalyzer(repo, props);
    }

    @Test
    @DisplayName("empty seed list returns empty graph")
    void emptySeedList() {
        PlanGraph g = analyzer.propagate(List.of());
        assertThat(g.size()).isZero();
    }

    @Test
    @DisplayName("MODIFY_METHOD_SIGNATURE propagates to each caller as a derived edit")
    void signatureChangePropagatesToCallers() {
        PlanNode seed = seed("com.example.Foo", ChangeKind.MODIFY_METHOD_SIGNATURE);
        when(repo.findCallersByQualifiedName("com.example.Foo"))
                .thenReturn(List.of(
                        Map.of("projectId", "proj-a",
                                "qualifiedName", "com.example.Caller1",
                                "filePath", "src/Caller1.java"),
                        Map.of("projectId", "proj-a",
                                "qualifiedName", "com.example.Caller2",
                                "filePath", "src/Caller2.java")));

        PlanGraph g = analyzer.propagate(List.of(seed));

        assertThat(g.seedNodes()).containsExactly(seed);
        assertThat(g.derivedNodes()).hasSize(2);
        assertThat(g.edges()).hasSize(2);
        assertThat(g.edges()).allMatch(e -> e.from().equals(seed.blockId()));
    }

    @Test
    @DisplayName("MODIFY_METHOD_BODY does NOT trigger propagation (body-only edits stay local)")
    void bodyChangeDoesNotPropagate() {
        PlanNode seed = seed("com.example.Foo", ChangeKind.MODIFY_METHOD_BODY);
        PlanGraph g = analyzer.propagate(List.of(seed));
        assertThat(g.derivedNodes()).isEmpty();
    }

    @Test
    @DisplayName("DELETE_METHOD propagates to callers")
    void deleteMethodPropagates() {
        PlanNode seed = seed("com.example.Foo", ChangeKind.DELETE_METHOD);
        when(repo.findCallersByQualifiedName(anyString()))
                .thenReturn(List.of(Map.of(
                        "projectId", "proj-b",
                        "qualifiedName", "com.example.Caller",
                        "filePath", "src/Caller.java")));
        PlanGraph g = analyzer.propagate(List.of(seed));
        assertThat(g.derivedNodes()).hasSize(1);
    }

    @Test
    @DisplayName("Self-reference (caller is the same symbol) is skipped")
    void skipsSelfReference() {
        PlanNode seed = seed("com.example.Foo", ChangeKind.MODIFY_METHOD_SIGNATURE);
        when(repo.findCallersByQualifiedName(anyString()))
                .thenReturn(List.of(Map.of(
                        "projectId", "proj-a",
                        "qualifiedName", "com.example.Foo",
                        "filePath", "src/Foo.java")));
        PlanGraph g = analyzer.propagate(List.of(seed));
        assertThat(g.derivedNodes()).isEmpty();
    }

    @Test
    @DisplayName("Max-plan-nodes cap truncates further propagation")
    void maxPlanNodesCapTruncates() {
        BrainProperties.Autodev autodev = new BrainProperties.Autodev(0.7, 5, 2, 10, false);
        BrainProperties props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, autodev, null, null, null, null, null, null);
        analyzer = new DependencyPropagationAnalyzer(repo, props);

        PlanNode seed = seed("com.example.Foo", ChangeKind.MODIFY_METHOD_SIGNATURE);
        when(repo.findCallersByQualifiedName(anyString()))
                .thenReturn(List.of(
                        Map.of("projectId", "p", "qualifiedName", "A", "filePath", "A.java"),
                        Map.of("projectId", "p", "qualifiedName", "B", "filePath", "B.java"),
                        Map.of("projectId", "p", "qualifiedName", "C", "filePath", "C.java")));

        PlanGraph g = analyzer.propagate(List.of(seed));
        assertThat(g.size()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Caller lookup failure does not abort the whole propagation")
    void toleratesLookupFailure() {
        PlanNode seed = seed("com.example.Foo", ChangeKind.MODIFY_METHOD_SIGNATURE);
        when(repo.findCallersByQualifiedName(anyString()))
                .thenThrow(new RuntimeException("neo4j down"));

        PlanGraph g = analyzer.propagate(List.of(seed));

        assertThat(g.seedNodes()).containsExactly(seed);
        assertThat(g.derivedNodes()).isEmpty();
    }

    @Test
    @DisplayName("Seed with null targetSymbol skips propagation even when kind triggers")
    void nullTargetSymbolSkipped() {
        PlanNode seed = new PlanNode("seed-1", "proj-a", "src/F.java", null,
                "change", ChangeKind.MODIFY_METHOD_SIGNATURE, false, null);
        PlanGraph g = analyzer.propagate(List.of(seed));
        assertThat(g.derivedNodes()).isEmpty();
    }

    @Test
    @DisplayName("triggersPropagation: signature/class/delete → true, body/add/import/null → false")
    void triggersPropagationMatrix() {
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.MODIFY_METHOD_SIGNATURE)).isTrue();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.MODIFY_CLASS)).isTrue();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.DELETE_METHOD)).isTrue();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.DELETE_CLASS)).isTrue();

        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.MODIFY_METHOD_BODY)).isFalse();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.ADD_METHOD)).isFalse();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.ADD_CLASS)).isFalse();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.MODIFY_IMPORT)).isFalse();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(ChangeKind.UNKNOWN)).isFalse();
        assertThat(DependencyPropagationAnalyzer.triggersPropagation(null)).isFalse();
    }

    private PlanNode seed(String qualifiedName, ChangeKind kind) {
        return new PlanNode("seed-" + qualifiedName, "proj-a", "src/F.java",
                qualifiedName, "change " + qualifiedName, kind, false, null);
    }
}
