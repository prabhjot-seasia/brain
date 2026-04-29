package com.assurant.brain.avenger;

import com.assurant.brain.avenger.dto.AvengerRequest;
import com.assurant.brain.avenger.dto.AvengerResponse;
import com.assurant.brain.avenger.dto.FullReviewResponse;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AvengerOrchestrator")
class AvengerOrchestratorTest {

    private AvengerReviewer reviewer;
    private AvengerOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        reviewer = mock(AvengerReviewer.class);
        Executor directExecutor = Runnable::run;
        orchestrator = new AvengerOrchestrator(reviewer, directExecutor,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("runs all 11 Avengers and aggregates verdicts")
    void runsAllEleven() {
        when(reviewer.review(any(AvengerRequest.class))).thenAnswer(i -> {
            AvengerRequest req = i.getArgument(0);
            return new AvengerResponse(UUID.randomUUID(), req.avenger(), AvengerVerdict.APPROVED,
                    java.util.List.of(), "ok", 10);
        });

        FullReviewResponse result = orchestrator.runFullReview("proj-1", "code", null);

        assertThat(result.totalAvengers()).isEqualTo(11);
        assertThat(result.approved()).isEqualTo(11);
        assertThat(result.overallVerdict()).isEqualTo(AvengerVerdict.APPROVED);
        verify(reviewer, times(11)).review(any(AvengerRequest.class));
    }

    @Test
    @DisplayName("overall verdict BLOCKED when any Avenger blocks")
    void overallBlockedOnBlock() {
        when(reviewer.review(any(AvengerRequest.class))).thenAnswer(i -> {
            AvengerRequest req = i.getArgument(0);
            AvengerVerdict v = req.avenger() == AvengerType.HAWKEYE ? AvengerVerdict.BLOCKED : AvengerVerdict.APPROVED;
            return new AvengerResponse(UUID.randomUUID(), req.avenger(), v, java.util.List.of(), "", 1);
        });

        FullReviewResponse result = orchestrator.runFullReview("proj-1", "code", null);
        assertThat(result.overallVerdict()).isEqualTo(AvengerVerdict.BLOCKED);
        assertThat(result.blocked()).isEqualTo(1);
    }

    @Test
    @DisplayName("overall verdict CHANGES_REQUESTED when no blocks but any changes")
    void overallChangesRequested() {
        when(reviewer.review(any(AvengerRequest.class))).thenAnswer(i -> {
            AvengerRequest req = i.getArgument(0);
            AvengerVerdict v = req.avenger() == AvengerType.STARK ? AvengerVerdict.CHANGES_REQUESTED : AvengerVerdict.APPROVED;
            return new AvengerResponse(UUID.randomUUID(), req.avenger(), v, java.util.List.of(), "", 1);
        });

        FullReviewResponse result = orchestrator.runFullReview("proj-1", "code", null);
        assertThat(result.overallVerdict()).isEqualTo(AvengerVerdict.CHANGES_REQUESTED);
    }
}
