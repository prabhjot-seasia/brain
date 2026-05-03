package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.SelfReviewLoop;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PrBatchRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PrBatch;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.BatchStatus;
import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.github.PrCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MultiRepoPrOrchestrator")
class MultiRepoPrOrchestratorTest {

    private PrCreationService prCreationService;
    private PullRequestRecordRepository prRecordRepository;
    private PrBatchRepository prBatchRepository;
    private SelfReviewLoop selfReviewLoop;
    private MultiRepoPrOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        prCreationService = mock(PrCreationService.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);
        prBatchRepository = mock(PrBatchRepository.class);
        selfReviewLoop = mock(SelfReviewLoop.class);

        var github = new BrainProperties.GitHub("token", "https://api.github.com", 3);
        var props = new BrainProperties(null, null, null, null, github, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        when(prBatchRepository.save(any(PrBatch.class))).thenAnswer(inv -> {
            PrBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });
        when(prRecordRepository.save(any(PullRequestRecord.class))).thenAnswer(inv -> {
            PullRequestRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(selfReviewLoop.run(any(PullRequestRecord.class), anyMap(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));

        var annotationsBuilder = mock(com.assurant.brain.codegen.PrAnnotationsBuilder.class);
        when(annotationsBuilder.renderPrBody(anyString(), anyMap(), anyString())).thenReturn("body");
        orchestrator = new MultiRepoPrOrchestrator(prCreationService, prRecordRepository,
                prBatchRepository, selfReviewLoop, props, annotationsBuilder, Runnable::run);
    }

    @Test
    @DisplayName("createBatch returns COMPLETED when every repo succeeds")
    void allReposSucceed() {
        when(prCreationService.createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PrCreationService.PrResult("brain/codegen-1", 1, "url1"))
                .thenReturn(new PrCreationService.PrResult("brain/codegen-2", 2, "url2"));

        MultiRepoPrResult result = orchestrator.createBatch(UUID.randomUUID(), List.of(
                target("proj-a", "https://github.com/o/a"),
                target("proj-b", "https://github.com/o/b")
        ), "{}");

        assertThat(result.overallStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("createBatch returns PARTIAL when one repo fails at PR_CREATE")
    void oneRepoFails() {
        when(prCreationService.createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(new PrCreationService.PrResult("brain/codegen-1", 1, "url1"))
                .thenThrow(new RuntimeException("github 500"));

        MultiRepoPrResult result = orchestrator.createBatch(UUID.randomUUID(), List.of(
                target("proj-a", "https://github.com/o/a"),
                target("proj-b", "https://github.com/o/b")
        ), "{}");

        assertThat(result.overallStatus()).isEqualTo(BatchStatus.PARTIAL);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.perRepo())
                .filteredOn(r -> r.outcome() == PerRepoOutcome.FAILED)
                .hasSize(1);
    }

    @Test
    @DisplayName("createBatch returns FAILED when every repo fails")
    void allReposFail() {
        when(prCreationService.createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenThrow(new RuntimeException("down"));

        MultiRepoPrResult result = orchestrator.createBatch(UUID.randomUUID(), List.of(
                target("proj-a", "https://github.com/o/a"),
                target("proj-b", "https://github.com/o/b")
        ), "{}");

        assertThat(result.overallStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(result.failed()).isEqualTo(2);
    }

    @Test
    @DisplayName("createBatch rejects an empty target list")
    void rejectsEmpty() {
        assertThatThrownBy(() -> orchestrator.createBatch(UUID.randomUUID(), List.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PerRepoResult.failed carries failureStage name")
    void perRepoFailureStage() {
        when(prCreationService.createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenThrow(new RuntimeException("github down"));

        MultiRepoPrResult result = orchestrator.createBatch(UUID.randomUUID(), List.of(
                target("proj-a", "https://github.com/o/a")), "{}");

        assertThat(result.perRepo().get(0).failureStage()).isNotNull();
        verify(prRecordRepository, atLeast(1)).save(argThat(r ->
                "PR_CREATE".equals(r.getFailureStage())));
    }

    private MultiRepoPrOrchestrator.RepoTarget target(String projectId, String repoUrl) {
        return new MultiRepoPrOrchestrator.RepoTarget(
                projectId, repoUrl, "main",
                Map.of("src/Foo.java", "content"), "summary");
    }
}
