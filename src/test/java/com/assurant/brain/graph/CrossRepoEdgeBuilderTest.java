package com.assurant.brain.graph;

import com.assurant.brain.graph.CrossRepoEdgeBuilder.ProjectIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CrossRepoEdgeBuilder")
class CrossRepoEdgeBuilderTest {

    private CrossRepoGraphOps graphOps;
    private CrossRepoEdgeBuilder builder;

    @BeforeEach
    void setup() {
        graphOps = mock(CrossRepoGraphOps.class);
        builder = new CrossRepoEdgeBuilder(graphOps);
    }

    @Test
    @DisplayName("returns 0 and does not touch the graph when the project is missing")
    void returnsZeroWhenProjectMissing() {
        when(graphOps.loadIdentity("ghost")).thenReturn(Optional.empty());

        int edges = builder.rebuildForProject("ghost");

        assertThat(edges).isZero();
        verify(graphOps, never()).clearDependenciesFor(anyString());
        verify(graphOps, never()).createEdge(anyString(), anyString());
    }

    @Test
    @DisplayName("clears existing DEPENDS_ON edges before rewriting")
    void clearsEdgesBeforeRewriting() {
        ProjectIdentity target = new ProjectIdentity("proj-a", "com.example", "artifact-a", null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());
        when(graphOps.findIncomingMatches(target)).thenReturn(List.of());

        builder.rebuildForProject("proj-a");

        verify(graphOps).clearDependenciesFor("proj-a");
    }

    @Test
    @DisplayName("outgoing matches become DEPENDS_ON edges from the target project")
    void writesOutgoingEdges() {
        ProjectIdentity target = new ProjectIdentity("proj-a", null, null, null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of("proj-b", "proj-c"));

        int edges = builder.rebuildForProject("proj-a");

        assertThat(edges).isEqualTo(2);
        verify(graphOps).createEdge("proj-a", "proj-b");
        verify(graphOps).createEdge("proj-a", "proj-c");
    }

    @Test
    @DisplayName("incoming matches are skipped when target has no identity fields")
    void skipsIncomingWhenNoIdentity() {
        ProjectIdentity target = new ProjectIdentity("proj-a", null, null, null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());

        int edges = builder.rebuildForProject("proj-a");

        assertThat(edges).isZero();
        verify(graphOps, never()).findIncomingMatches(any());
    }

    @Test
    @DisplayName("incoming matches write DEPENDS_ON edges toward the target project")
    void writesIncomingEdges() {
        ProjectIdentity target = new ProjectIdentity("proj-a", "com.example", "artifact-a", null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());
        when(graphOps.findIncomingMatches(target)).thenReturn(List.of("proj-x", "proj-y"));

        int edges = builder.rebuildForProject("proj-a");

        assertThat(edges).isEqualTo(2);
        verify(graphOps).createEdge("proj-x", "proj-a");
        verify(graphOps).createEdge("proj-y", "proj-a");
    }

    @Test
    @DisplayName("npm-only identity still triggers the incoming match query")
    void npmOnlyTriggersIncoming() {
        ProjectIdentity target = new ProjectIdentity("proj-a", null, null, "@scope/proj-a");
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());
        when(graphOps.findIncomingMatches(target)).thenReturn(List.of("proj-consumer"));

        int edges = builder.rebuildForProject("proj-a");

        assertThat(edges).isEqualTo(1);
        verify(graphOps).createEdge("proj-consumer", "proj-a");
    }

    @Test
    @DisplayName("groupId-without-artifactId does not count as a matchable identity")
    void partialMavenIdentityIsNotMatchable() {
        ProjectIdentity target = new ProjectIdentity("proj-a", "com.example", null, null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());

        builder.rebuildForProject("proj-a");

        verify(graphOps, never()).findIncomingMatches(any());
    }

    @Test
    @DisplayName("rebuildAll walks every project in the graph and accumulates edge counts")
    void rebuildAllAccumulates() {
        when(graphOps.listAllProjectIds()).thenReturn(List.of("proj-a", "proj-b"));
        ProjectIdentity a = new ProjectIdentity("proj-a", null, null, null);
        ProjectIdentity b = new ProjectIdentity("proj-b", null, null, null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(a));
        when(graphOps.loadIdentity("proj-b")).thenReturn(Optional.of(b));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of("proj-b"));
        when(graphOps.findOutgoingMatches("proj-b")).thenReturn(List.of());

        int total = builder.rebuildAll();

        assertThat(total).isEqualTo(1);
        verify(graphOps).clearDependenciesFor("proj-a");
        verify(graphOps).clearDependenciesFor("proj-b");
    }

    @Test
    @DisplayName("hasAnyIdentity: true when both Maven coordinates are present")
    void hasAnyIdentityMaven() {
        assertThat(CrossRepoEdgeBuilder.hasAnyIdentity(
                new ProjectIdentity("p", "g", "a", null))).isTrue();
    }

    @Test
    @DisplayName("hasAnyIdentity: true when only npmName is present")
    void hasAnyIdentityNpm() {
        assertThat(CrossRepoEdgeBuilder.hasAnyIdentity(
                new ProjectIdentity("p", null, null, "@scope/pkg"))).isTrue();
    }

    @Test
    @DisplayName("hasAnyIdentity: false when no matchable fields are present")
    void hasAnyIdentityNone() {
        assertThat(CrossRepoEdgeBuilder.hasAnyIdentity(
                new ProjectIdentity("p", null, null, null))).isFalse();
        assertThat(CrossRepoEdgeBuilder.hasAnyIdentity(
                new ProjectIdentity("p", "g", null, null))).isFalse();
        assertThat(CrossRepoEdgeBuilder.hasAnyIdentity(
                new ProjectIdentity("p", null, "a", null))).isFalse();
    }

    @Test
    @DisplayName("ProjectIdentity record exposes all four components")
    void projectIdentityRecord() {
        ProjectIdentity id = new ProjectIdentity("p", "g", "a", "@s/p");
        assertThat(id.id()).isEqualTo("p");
        assertThat(id.groupId()).isEqualTo("g");
        assertThat(id.artifactId()).isEqualTo("a");
        assertThat(id.npmName()).isEqualTo("@s/p");
    }

    @Test
    @DisplayName("outgoing match to self is not specifically filtered (relies on Cypher WHERE id <>)")
    void outgoingMatchReliesOnCypherFilter() {
        ProjectIdentity target = new ProjectIdentity("proj-a", null, null, null);
        when(graphOps.loadIdentity("proj-a")).thenReturn(Optional.of(target));
        when(graphOps.findOutgoingMatches("proj-a")).thenReturn(List.of());

        builder.rebuildForProject("proj-a");

        verify(graphOps).findOutgoingMatches(eq("proj-a"));
    }
}
