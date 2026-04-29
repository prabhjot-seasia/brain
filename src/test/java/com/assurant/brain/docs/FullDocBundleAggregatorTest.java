package com.assurant.brain.docs;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FullDocBundleAggregator")
class FullDocBundleAggregatorTest {

    @Mock private ProjectNodeRepository projectRepo;
    @Mock private ConventionNodeRepository conventionRepo;
    @Mock private IncidentNodeRepository incidentRepo;
    @Mock private SLONodeRepository sloRepo;
    @Mock private TestRunNodeRepository testRunRepo;
    @Mock private CommunitySummaryNodeRepository communityRepo;
    @Mock private PullRequestRecordRepository prRepo;
    @Mock private com.assurant.brain.config.properties.BrainProperties brainProperties;

    @InjectMocks private FullDocBundleAggregator aggregator;

    private ProjectNode project;

    @BeforeEach
    void setup() {
        project = new ProjectNode();
        project.setId("ce-imei");
        project.setName("CE IMEI");
    }

    @Test
    @DisplayName("aggregates every source when all repos return data")
    void allRepositoriesPopulated() {
        ConventionNode conv = new ConventionNode();
        IncidentNode inc = new IncidentNode();
        SLONode slo = new SLONode();
        TestRunNode flaky = new TestRunNode();
        CommunitySummaryNode comm = new CommunitySummaryNode();
        PullRequestRecord pr = new PullRequestRecord();

        when(projectRepo.findById("ce-imei")).thenReturn(Optional.of(project));
        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc("ce-imei")).thenReturn(List.of(conv));
        when(incidentRepo.findByProjectIdAndClassHintMatch(eq("ce-imei"), anyString())).thenReturn(List.of(inc));
        when(sloRepo.findByProjectIdOrderByTargetDesc("ce-imei")).thenReturn(List.of(slo));
        when(testRunRepo.findFlakyTests(eq("ce-imei"), anyDouble(), anyInt())).thenReturn(List.of(flaky));
        when(communityRepo.findByProjectId("ce-imei")).thenReturn(List.of(comm));
        when(prRepo.findByProjectIdOrderByCreatedAtDesc("ce-imei")).thenReturn(List.of(pr));

        var agg = aggregator.aggregate("ce-imei");

        assertThat(agg.project()).isSameAs(project);
        assertThat(agg.conventions()).containsExactly(conv);
        assertThat(agg.incidents()).containsExactly(inc);
        assertThat(agg.slos()).containsExactly(slo);
        assertThat(agg.flakyTests()).containsExactly(flaky);
        assertThat(agg.communitySummaries()).containsExactly(comm);
        assertThat(agg.recentPullRequests()).containsExactly(pr);
    }

    @Test
    @DisplayName("missing ProjectNode degrades to null without throwing")
    void missingProjectIsSilent() {
        when(projectRepo.findById("missing")).thenReturn(Optional.empty());
        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(List.of());
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of());
        when(testRunRepo.findFlakyTests(anyString(), anyDouble(), anyInt())).thenReturn(List.of());
        when(communityRepo.findByProjectId(anyString())).thenReturn(List.of());
        when(prRepo.findByProjectIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        var agg = aggregator.aggregate("missing");

        assertThat(agg.project()).isNull();
        assertThat(agg.conventions()).isEmpty();
    }

    @Test
    @DisplayName("repository RuntimeException degrades to empty list per source")
    void perSourceFailureDegradesToEmpty() {
        when(projectRepo.findById(anyString())).thenThrow(new RuntimeException("graph down"));
        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc(anyString()))
                .thenThrow(new RuntimeException("conv down"));
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(List.of());
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of());
        when(testRunRepo.findFlakyTests(anyString(), anyDouble(), anyInt())).thenReturn(List.of());
        when(communityRepo.findByProjectId(anyString())).thenReturn(List.of());
        when(prRepo.findByProjectIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        var agg = aggregator.aggregate("ce-imei");

        assertThat(agg.project()).isNull();
        assertThat(agg.conventions()).isEmpty();
    }

    @Test
    @DisplayName("PullRequestRecord list capped at 20 entries")
    void prListCapped() {
        when(projectRepo.findById(anyString())).thenReturn(Optional.of(project));
        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(List.of());
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of());
        when(testRunRepo.findFlakyTests(anyString(), anyDouble(), anyInt())).thenReturn(List.of());
        when(communityRepo.findByProjectId(anyString())).thenReturn(List.of());
        List<PullRequestRecord> fifty = IntStream.range(0, 50)
                .mapToObj(i -> new PullRequestRecord()).toList();
        when(prRepo.findByProjectIdOrderByCreatedAtDesc(anyString())).thenReturn(fifty);

        var agg = aggregator.aggregate("ce-imei");
        assertThat(agg.recentPullRequests()).hasSize(20);
    }

    @Test
    @DisplayName("repository returning null is treated as empty list (not NPE)")
    void nullCollectionTreatedAsEmpty() {
        when(projectRepo.findById(anyString())).thenReturn(Optional.of(project));
        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(null);
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(null);
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(null);
        when(testRunRepo.findFlakyTests(anyString(), anyDouble(), anyInt())).thenReturn(null);
        when(communityRepo.findByProjectId(anyString())).thenReturn(null);
        when(prRepo.findByProjectIdOrderByCreatedAtDesc(anyString())).thenReturn(null);

        var agg = aggregator.aggregate("ce-imei");

        assertThat(agg.conventions()).isEmpty();
        assertThat(agg.incidents()).isEmpty();
        assertThat(agg.slos()).isEmpty();
        assertThat(agg.flakyTests()).isEmpty();
        assertThat(agg.communitySummaries()).isEmpty();
        assertThat(agg.recentPullRequests()).isEmpty();
    }
}
