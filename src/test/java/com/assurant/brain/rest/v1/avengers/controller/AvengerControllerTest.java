package com.assurant.brain.rest.v1.avengers.controller;

import com.assurant.brain.avenger.AvengerOrchestrator;
import com.assurant.brain.avenger.AvengerReviewer;
import com.assurant.brain.avenger.dto.AvengerRequest;
import com.assurant.brain.avenger.dto.AvengerResponse;
import com.assurant.brain.avenger.dto.FullReviewResponse;
import com.assurant.brain.dao.AvengerReviewRepository;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.assurant.brain.rest.v1.avengers.dto.AvengerReviewRequest;
import com.assurant.brain.rest.v1.avengers.dto.FullReviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AvengerController")
class AvengerControllerTest {

    private AvengerReviewer reviewer;
    private AvengerOrchestrator orchestrator;
    private AvengerReviewRepository repository;
    private com.assurant.brain.jobs.AsyncJobService asyncJobService;
    private AvengerController controller;

    @BeforeEach
    void setup() {
        reviewer = mock(AvengerReviewer.class);
        orchestrator = mock(AvengerOrchestrator.class);
        repository = mock(AvengerReviewRepository.class);
        asyncJobService = mock(com.assurant.brain.jobs.AsyncJobService.class);
        controller = new AvengerController(reviewer, orchestrator, repository, asyncJobService);
    }

    @Test
    @DisplayName("review endpoint delegates to reviewer")
    void reviewDelegates() {
        AvengerResponse expected = new AvengerResponse(UUID.randomUUID(), AvengerType.STARK,
                AvengerVerdict.APPROVED, List.of(), "ok", 5L);
        when(reviewer.review(any(AvengerRequest.class))).thenReturn(expected);

        ResponseEntity<AvengerResponse> response = controller.review(
                AvengerType.STARK, new AvengerReviewRequest("p", "code", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().verdict()).isEqualTo(AvengerVerdict.APPROVED);
    }

    @Test
    @DisplayName("full-review endpoint delegates to orchestrator")
    void fullReviewDelegates() {
        FullReviewResponse expected = new FullReviewResponse(AvengerVerdict.APPROVED, 11, 11, 0, 0, 100L, List.of());
        when(orchestrator.runFullReview(eq("p"), eq("code"), any())).thenReturn(expected);

        ResponseEntity<FullReviewResponse> response = controller.fullReview(new FullReviewRequest("p", "code", null));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().totalAvengers()).isEqualTo(11);
    }

    @Test
    @DisplayName("history endpoint returns repository results")
    void historyReturnsResults() {
        when(repository.findByAvengerAndProjectIdOrderByCreatedAtDesc(eq(AvengerType.STARK), eq("p"), any(Pageable.class)))
                .thenReturn(List.of());
        ResponseEntity<?> response = controller.history(AvengerType.STARK, "p", 20);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
