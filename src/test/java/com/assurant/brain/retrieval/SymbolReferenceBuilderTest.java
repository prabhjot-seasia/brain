package com.assurant.brain.retrieval;

import com.assurant.brain.graph.node.SymbolReferenceNode;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SymbolReferenceBuilder")
class SymbolReferenceBuilderTest {

    private SymbolReferenceNodeRepository repo;
    private SymbolReferenceBuilder builder;

    @BeforeEach
    void setup() {
        repo = mock(SymbolReferenceNodeRepository.class);
        builder = new SymbolReferenceBuilder(repo);
        when(repo.saveAll(any(Iterable.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("rebuildForClass emits one CLASS node + one node per member")
    void rebuildEmitsClassAndMembers() {
        var nodes = builder.rebuildForClass("proj-1", "com.example.OrderService", "java", List.of(
                new SymbolReferenceBuilder.Member("place", "method"),
                new SymbolReferenceBuilder.Member("cancel", "method"),
                new SymbolReferenceBuilder.Member("orderId", "field")));

        assertThat(nodes).hasSize(4);
        assertThat(nodes.get(0).getKind()).isEqualTo("CLASS");
        assertThat(nodes.get(0).getSymbolFqn()).isEqualTo("com.example.OrderService");
        assertThat(nodes).anyMatch(n -> n.getSymbolFqn().equals("com.example.OrderService#place")
                && n.getKind().equals("METHOD"));
        assertThat(nodes).anyMatch(n -> n.getSymbolFqn().equals("com.example.OrderService#orderId")
                && n.getKind().equals("FIELD"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SymbolReferenceNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(4);
    }

    @Test
    @DisplayName("each saved node has scipScheme=scip-java and stable id")
    void schemeAndIdSet() {
        var nodes = builder.rebuildForClass("proj-1", "com.example.Foo", null, List.of());
        assertThat(nodes.get(0).getScipScheme()).isEqualTo("scip-java");
        assertThat(nodes.get(0).getLanguage()).isEqualTo("java");
        assertThat(nodes.get(0).getId()).isEqualTo("proj-1:com.example.Foo");
    }

    @Test
    @DisplayName("rebuildForClass tolerates null members list")
    void nullMembers() {
        var nodes = builder.rebuildForClass("proj-1", "com.example.Foo", "java", null);
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getKind()).isEqualTo("CLASS");
    }

    @Test
    @DisplayName("rebuildForClass returns empty list for null inputs")
    void nullInputsReturnEmpty() {
        assertThat(builder.rebuildForClass(null, "X", "java", List.of())).isEmpty();
        assertThat(builder.rebuildForClass("p", null, "java", List.of())).isEmpty();
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).saveAll(any(Iterable.class));
    }

    @Test
    @DisplayName("blank member name is silently filtered")
    void blankMemberNameSkipped() {
        var nodes = builder.rebuildForClass("p", "com.example.Foo", "java", List.of(
                new SymbolReferenceBuilder.Member("realMethod", "method"),
                new SymbolReferenceBuilder.Member("  ", "method")));

        assertThat(nodes).hasSize(2);
    }

    @Test
    @DisplayName("findCallSites returns empty for blank fqn without calling repo")
    void findCallSitesBlankFqn() {
        assertThat(builder.findCallSites("")).isEmpty();
        assertThat(builder.findCallSites(null)).isEmpty();
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never())
                .findBySymbolFqn(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("findCallSites returns empty when repo errors")
    void findFallsBackOnError() {
        when(repo.findBySymbolFqn("x")).thenThrow(new RuntimeException("neo4j down"));
        assertThat(builder.findCallSites("x")).isEmpty();
    }
}
