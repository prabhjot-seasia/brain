package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.graph.node.CommunitySummaryNode;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SLONode;
import com.assurant.brain.graph.node.TestRunNode;
import com.assurant.brain.graph.repository.CommunitySummaryNodeRepository;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.SLONodeRepository;
import com.assurant.brain.graph.repository.TestRunNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class FullDocBundleAggregator {

    private static final int DEFAULT_MAX_PRS_INCLUDED = 20;

    private final BrainProperties brainProperties;
    private final ProjectNodeRepository projectNodeRepository;
    private final ConventionNodeRepository conventionNodeRepository;
    private final IncidentNodeRepository incidentNodeRepository;
    private final SLONodeRepository sloNodeRepository;
    private final TestRunNodeRepository testRunNodeRepository;
    private final CommunitySummaryNodeRepository communitySummaryNodeRepository;
    private final PullRequestRecordRepository pullRequestRecordRepository;

    public record FullDocAggregate(
            ProjectNode project,
            List<ConventionNode> conventions,
            List<IncidentNode> incidents,
            List<SLONode> slos,
            List<TestRunNode> flakyTests,
            List<CommunitySummaryNode> communitySummaries,
            List<PullRequestRecord> recentPullRequests
    ) {}

    public FullDocAggregate aggregate(String projectId) {
        Optional<ProjectNode> project = safeFindProject(projectId);
        return new FullDocAggregate(
                project.orElse(null),
                safe(() -> conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(projectId), List.of()),
                safe(() -> findIncidents(projectId), List.of()),
                safe(() -> sloNodeRepository.findByProjectIdOrderByTargetDesc(projectId), List.of()),
                safe(() -> testRunNodeRepository.findFlakyTests(projectId, 0.0, 50), List.of()),
                safe(() -> communitySummaryNodeRepository.findByProjectId(projectId), List.of()),
                safe(() -> recentPullRequests(projectId), List.<PullRequestRecord>of())
        );
    }

    private List<PullRequestRecord> recentPullRequests(String projectId) {
        List<PullRequestRecord> scoped = pullRequestRecordRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        int cap = maxPrs();
        return scoped.size() <= cap ? scoped : scoped.subList(0, cap);
    }

    private int maxPrs() {
        if (brainProperties.docs() == null || brainProperties.docs().maxPrsIncluded() <= 0) {
            return DEFAULT_MAX_PRS_INCLUDED;
        }
        return brainProperties.docs().maxPrsIncluded();
    }

    private Optional<ProjectNode> safeFindProject(String projectId) {
        try {
            return projectNodeRepository.findById(projectId);
        } catch (RuntimeException e) {
            log.debug("aggregate: failed to load ProjectNode for {}: {}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private List<IncidentNode> findIncidents(String projectId) {
        return incidentNodeRepository.findByProjectIdAndClassHintMatch(projectId, "");
    }

    private <T> T safe(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            log.debug("aggregate: graph query failed: {}", e.getMessage());
            return fallback;
        }
    }
}
