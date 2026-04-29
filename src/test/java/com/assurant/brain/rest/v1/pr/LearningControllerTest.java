package com.assurant.brain.rest.v1.pr;

import com.assurant.brain.dao.CiRemediationAttemptRepository;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.CiRemediationAttempt;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.learning.MergedPrAnalyzerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LearningController")
class LearningControllerTest {

    private MergedPrAnalyzerService analyzer;
    private CiRemediationAttemptRepository remediationRepo;
    private LearningEventRepository learningRepo;
    private LearningController controller;

    @BeforeEach
    void setup() {
        analyzer = mock(MergedPrAnalyzerService.class);
        remediationRepo = mock(CiRemediationAttemptRepository.class);
        learningRepo = mock(LearningEventRepository.class);
        controller = new LearningController(analyzer, remediationRepo, learningRepo);
    }

    @Test
    @DisplayName("analyzeMergedPr delegates to MergedPrAnalyzerService")
    void analyzeDelegates() {
        UUID id = UUID.randomUUID();
        LearningEvent ev = new LearningEvent();
        when(analyzer.analyzeAndLearn(id, "ce-imei")).thenReturn(List.of(ev));
        var resp = controller.analyzeMergedPr(id, "ce-imei");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsExactly(ev);
    }

    @Test
    @DisplayName("getRemediations returns ordered list from repo")
    void getRemediations() {
        UUID id = UUID.randomUUID();
        CiRemediationAttempt attempt = new CiRemediationAttempt();
        when(remediationRepo.findByPrRecordIdOrderByAttemptNumberAsc(id)).thenReturn(List.of(attempt));
        var resp = controller.getRemediations(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsExactly(attempt);
    }

    @Test
    @DisplayName("getLearningEvents with projectId scopes to project")
    void getLearningEventsScopedToProject() {
        LearningEvent ev = new LearningEvent();
        when(learningRepo.findByProjectIdOrderByCreatedAtDesc("ce-imei")).thenReturn(List.of(ev));
        var resp = controller.getLearningEvents("ce-imei");
        assertThat(resp.getBody()).containsExactly(ev);
        verify(learningRepo).findByProjectIdOrderByCreatedAtDesc("ce-imei");
    }

    @Test
    @DisplayName("getLearningEvents without projectId returns all")
    void getLearningEventsAll() {
        LearningEvent ev = new LearningEvent();
        when(learningRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(ev));
        var resp = controller.getLearningEvents(null);
        assertThat(resp.getBody()).containsExactly(ev);
    }

    @Test
    @DisplayName("getLearningEventsByPr scopes to PR record")
    void getLearningEventsByPr() {
        UUID prId = UUID.randomUUID();
        when(learningRepo.findByPrRecordIdOrderByCreatedAtDesc(prId)).thenReturn(List.of());
        var resp = controller.getLearningEventsByPr(prId);
        assertThat(resp.getBody()).isEmpty();
        verifyNoInteractions(analyzer);
    }
}
