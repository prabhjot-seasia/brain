package com.assurant.brain.rest.v1.pr;

import com.assurant.brain.dao.CodeReviewIterationRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CodeReviewIteration;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.dto.request.CreatePrRequest;
import com.assurant.brain.facade.PrCreationFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PrController")
class PrControllerTest {

    private PrCreationFacade prCreationFacade;
    private PullRequestRecordRepository prRecordRepository;
    private CodeReviewIterationRepository reviewIterationRepository;
    private PrController controller;

    @BeforeEach
    void setup() {
        prCreationFacade = mock(PrCreationFacade.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);
        reviewIterationRepository = mock(CodeReviewIterationRepository.class);
        controller = new PrController(prCreationFacade, prRecordRepository, reviewIterationRepository);
    }

    private PullRequestRecord stubRecord() {
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(UUID.randomUUID());
        pr.setSessionId(UUID.randomUUID());
        pr.setRepoUrl("https://github.com/example/repo");
        pr.setBranchName("brain/test");
        pr.setStatus(com.assurant.brain.enums.PrStatus.CREATED);
        pr.setCreatedAt(OffsetDateTime.now());
        return pr;
    }

    @Test
    @DisplayName("createPr returns 201 with PrRecordResponse")
    void createPrReturns201() {
        PullRequestRecord stub = stubRecord();
        when(prCreationFacade.createPr(any(UUID.class), anyString(), anyString())).thenReturn(stub);

        var resp = controller.createPr(new CreatePrRequest(
                UUID.randomUUID().toString(), "https://github.com/example/repo", "main"));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody().id()).isEqualTo(stub.getId().toString());
    }

    @Test
    @DisplayName("getPr returns 200 when record exists")
    void getPrFound() {
        PullRequestRecord stub = stubRecord();
        when(prRecordRepository.findById(stub.getId())).thenReturn(Optional.of(stub));
        var resp = controller.getPr(stub.getId());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().id()).isEqualTo(stub.getId().toString());
    }

    @Test
    @DisplayName("getPr returns 404 when record absent")
    void getPrNotFound() {
        UUID id = UUID.randomUUID();
        when(prRecordRepository.findById(id)).thenReturn(Optional.empty());
        var resp = controller.getPr(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("listPrs with sessionId scopes by session")
    void listPrsBySession() {
        PullRequestRecord stub = stubRecord();
        UUID session = stub.getSessionId();
        when(prRecordRepository.findBySessionIdOrderByCreatedAtDesc(session)).thenReturn(List.of(stub));
        var resp = controller.listPrs(session);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("listPrs without sessionId returns all")
    void listPrsAll() {
        when(prRecordRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(stubRecord(), stubRecord()));
        var resp = controller.listPrs(null);
        assertThat(resp.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("getReviewIterations returns ordered iterations from repo")
    void getReviewIterations() {
        UUID id = UUID.randomUUID();
        CodeReviewIteration it = new CodeReviewIteration();
        when(reviewIterationRepository.findByPrRecordIdOrderByIterationNumberAsc(id)).thenReturn(List.of(it));
        var resp = controller.getReviewIterations(id);
        assertThat(resp.getBody()).containsExactly(it);
    }
}
