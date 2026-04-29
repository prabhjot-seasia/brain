package com.assurant.brain.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class CrossRepoEdgeBuilder {

    private final CrossRepoGraphOps graphOps;

    public int rebuildForProject(String projectId) {
        Optional<ProjectIdentity> maybeTarget = graphOps.loadIdentity(projectId);
        if (maybeTarget.isEmpty()) {
            log.warn("Skipping DEPENDS_ON rebuild for project={} — not found in graph", projectId);
            return 0;
        }
        ProjectIdentity target = maybeTarget.get();

        graphOps.clearDependenciesFor(projectId);

        int outgoing = writeOutgoingDependencies(projectId);
        int incoming = writeIncomingDependencies(target);

        log.info("DEPENDS_ON rebuild for project={} wrote {} outgoing + {} incoming edge(s)",
                projectId, outgoing, incoming);
        return outgoing + incoming;
    }

    public int rebuildAll() {
        Collection<String> ids = graphOps.listAllProjectIds();
        int total = 0;
        for (String id : ids) {
            total += rebuildForProject(id);
        }
        log.info("Full DEPENDS_ON rebuild across {} project(s) wrote {} edge(s) total",
                ids.size(), total);
        return total;
    }

    public int rebuildServiceLinkages(String projectId) {
        graphOps.clearServiceInferenceFor(projectId);
        int linked = graphOps.linkServicesToProjects();
        log.info("ServiceNode -> Project inference for project={} linked {} services across the graph",
                projectId, linked);
        return linked;
    }

    private int writeOutgoingDependencies(String projectId) {
        Collection<String> toIds = graphOps.findOutgoingMatches(projectId);
        int count = 0;
        for (String toId : toIds) {
            graphOps.createEdge(projectId, toId);
            count++;
        }
        return count;
    }

    private int writeIncomingDependencies(ProjectIdentity target) {
        if (!hasAnyIdentity(target)) return 0;
        Collection<String> fromIds = graphOps.findIncomingMatches(target);
        int count = 0;
        for (String fromId : fromIds) {
            graphOps.createEdge(fromId, target.id());
            count++;
        }
        return count;
    }

    static boolean hasAnyIdentity(ProjectIdentity identity) {
        return (identity.groupId() != null && identity.artifactId() != null)
                || identity.npmName() != null;
    }

    public record ProjectIdentity(String id, String groupId, String artifactId, String npmName) {}
}
