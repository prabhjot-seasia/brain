package com.assurant.brain.facade;

import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.dto.request.AnalyzeRequest;
import com.assurant.brain.dto.response.AnalyzeResponse;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.exceptions.SessionNotFoundException;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AnalysisFacade — full analyze flow")
class AnalysisFacadeTest {

    private ClarifierService clarifierService;
    private PlannerService plannerService;
    private ClarificationSessionRepository sessionRepository;
    private AnalysisFacade facade;

    @BeforeEach
    void setup() {
        clarifierService = mock(ClarifierService.class);
        plannerService = mock(PlannerService.class);
        sessionRepository = mock(ClarificationSessionRepository.class);
        com.assurant.brain.service.ProjectAffinityDetector detector =
                mock(com.assurant.brain.service.ProjectAffinityDetector.class);
        when(detector.detect(any())).thenReturn(java.util.List.of());
        var brainProps = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, new com.assurant.brain.config.properties.BrainProperties.Clarifier(0.75, 0.5, 3, 5, 5), null, null, null, null, null, null);
        facade = new AnalysisFacade(clarifierService, plannerService, sessionRepository, new ObjectMapper(), detector, brainProps,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("EXPLAIN intent routes to explainProject, skipping clarifier")
    void explainIntentSkipsClarifier() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);
        when(plannerService.explainProject("proj-1", "Explain this project"))
                .thenReturn("## Overview\nSpring Boot app...");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Explain this project", null, null));

        assertThat(response.isPlanReady()).isTrue();
        assertThat(response.getKind()).isEqualTo(AnalyzeResponse.ResponseKind.EXPLAIN);
        assertThat(response.getPlan()).contains("Spring Boot");
        verify(clarifierService, never()).analyze(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("IMPLEMENT intent with confident clarifier generates plan immediately")
    void implementConfidentGeneratesPlan() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                """);
        when(clarifierService.analyze(eq("proj-1"), anyString(), anyList())).thenReturn(confident);
        when(plannerService.generatePlan(eq("proj-1"), anyString(), anyList()))
                .thenReturn("{\"requirement\":\"Add auth\",\"steps\":[]}");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Add authentication", null, null));

        assertThat(response.isPlanReady()).isTrue();
        assertThat(response.getKind()).isEqualTo(AnalyzeResponse.ResponseKind.IMPLEMENT);
        assertThat(response.isForcedAfterMaxRounds()).isFalse();
    }

    @Test
    @DisplayName("Not-confident clarifier returns questions for clarification")
    void notConfidentReturnsClarification() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse notConfident = ClarificationResponse.fromRawJson("""
                {"confident":false,"dimensions":{"why":{"score":0.3,"summary":"unclear"},"what":{"score":0.5,"summary":"ok"},"where":{"score":0.2,"summary":"?"},"how":{"score":0.4,"summary":"?"}},"unknownReferences":[],"questions":["Which module?"]}
                """);
        when(clarifierService.analyze(eq("proj-1"), anyString(), anyList())).thenReturn(notConfident);

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Add feature", null, null));

        assertThat(response.isPlanReady()).isFalse();
        assertThat(response.getQuestions()).hasSize(1);
        assertThat(response.getQuestions().get(0).text()).isEqualTo("Which module?");
        verify(plannerService, never()).generatePlan(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("After max clarification rounds, plan is force-generated")
    void forceGenerateAfterMaxRounds() {
        List<Map<String, Object>> fiveRounds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fiveRounds.add(Map.of("questions", "Q" + i, "answers", "A" + i));
        }
        ClarificationSession session = newSession("proj-1");
        session.setRounds(fiveRounds);

        UUID sessionId = session.getId();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse stillNotConfident = ClarificationResponse.fromRawJson("""
                {"confident":false,"dimensions":{"why":{"score":0.5,"summary":"ok"},"what":{"score":0.5,"summary":"ok"},"where":{"score":0.5,"summary":"ok"},"how":{"score":0.5,"summary":"ok"}},"unknownReferences":[],"questions":["Still unsure"]}
                """);
        when(clarifierService.analyze(eq("proj-1"), anyString(), anyList())).thenReturn(stillNotConfident);
        when(plannerService.generatePlan(eq("proj-1"), anyString(), anyList()))
                .thenReturn("{\"forced\":true}");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Complex feature", sessionId.toString(), null));

        assertThat(response.isPlanReady()).isTrue();
        assertThat(response.isForcedAfterMaxRounds()).isTrue();
    }

    @Test
    @DisplayName("Answers are appended to the last round before re-clarification")
    void answersAppendedToLastRound() {
        List<Map<String, Object>> rounds = new ArrayList<>();
        rounds.add(new java.util.HashMap<>(Map.of("questions", "Which DB?", "answers", "")));
        ClarificationSession session = newSession("proj-1");
        session.setRounds(rounds);

        UUID sessionId = session.getId();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                """);
        when(clarifierService.analyze(eq("proj-1"), anyString(), anyList())).thenReturn(confident);
        when(plannerService.generatePlan(eq("proj-1"), anyString(), anyList()))
                .thenReturn("{\"ok\":true}");

        facade.analyze(new AnalyzeRequest("proj-1", "Add caching", sessionId.toString(), "PostgreSQL"));

        verify(sessionRepository, atLeast(2)).save(argThat(s ->
                s.getRounds().get(0).get("answers").equals("PostgreSQL")));
    }

    @Test
    @DisplayName("Invalid session ID throws SessionNotFoundException")
    void invalidSessionThrows() {
        String fakeId = UUID.randomUUID().toString();
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.analyze(
                new AnalyzeRequest("proj-1", "Add feature", fakeId, null)))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("parsePlanToMap handles valid JSON")
    void parsePlanValidJson() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                """);
        when(clarifierService.analyze(anyString(), anyString(), anyList())).thenReturn(confident);
        when(plannerService.generatePlan(anyString(), anyString(), anyList()))
                .thenReturn("{\"requirement\":\"test\",\"steps\":[{\"order\":1}]}");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Add feature", null, null));

        assertThat(response.isPlanReady()).isTrue();
        verify(sessionRepository, atLeastOnce()).save(argThat(s ->
                s.getStatus() == SessionStatus.COMPLETE
                        && s.getFinalPlan() != null
                        && s.getFinalPlan().containsKey("requirement")));
    }

    @Test
    @DisplayName("parsePlanToMap handles markdown-fenced JSON from LLM")
    void parsePlanMarkdownFencedJson() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                """);
        when(clarifierService.analyze(anyString(), anyString(), anyList())).thenReturn(confident);
        when(plannerService.generatePlan(anyString(), anyString(), anyList()))
                .thenReturn("```json\n{\"requirement\":\"test\"}\n```");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Add feature", null, null));

        assertThat(response.isPlanReady()).isTrue();
        verify(sessionRepository, atLeastOnce()).save(argThat(s ->
                s.getFinalPlan() != null && s.getFinalPlan().containsKey("requirement")));
    }

    @Test
    @DisplayName("parsePlanToMap handles truncated JSON (JsonEOFException)")
    void parsePlanTruncatedJson() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                """);
        when(clarifierService.analyze(anyString(), anyString(), anyList())).thenReturn(confident);
        when(plannerService.generatePlan(anyString(), anyString(), anyList()))
                .thenReturn("{\"requirement\":\"test\",\"steps\":[{\"order\":");

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Add feature", null, null));

        assertThat(response.isPlanReady()).isTrue();
        verify(sessionRepository, atLeastOnce()).save(argThat(s ->
                s.getFinalPlan() != null && s.getFinalPlan().containsKey("raw")));
    }

    @Test
    @DisplayName("unknownReferences in clarification response blocks confidence")
    void unknownReferencesBlockConfidence() {
        ClarificationSession session = newSession("proj-1");
        when(sessionRepository.save(any())).thenReturn(session);

        ClarificationResponse withUnknowns = ClarificationResponse.fromRawJson("""
                {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":["mystery-service"],"questions":["What is mystery-service?"]}
                """);
        when(clarifierService.analyze(anyString(), anyString(), anyList())).thenReturn(withUnknowns);

        AnalyzeResponse response = facade.analyze(
                new AnalyzeRequest("proj-1", "Integrate with mystery-service", null, null));

        assertThat(response.isPlanReady()).isFalse();
        assertThat(response.getUnknownReferences()).contains("mystery-service");
    }

    private ClarificationSession newSession(String projectId) {
        ClarificationSession session = new ClarificationSession();
        session.setId(UUID.randomUUID());
        session.setProjectId(projectId);
        session.setRequirement("test requirement");
        session.setRounds(new ArrayList<>());
        session.setStatus(SessionStatus.PENDING);
        return session;
    }
}
