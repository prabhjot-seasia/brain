package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectAffinityDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

public class AutodevSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SharedTestState state;
    @Autowired private ClarificationSessionRepository sessionRepository;

    @Autowired private ClarifierService clarifierService;
    @Autowired private PlannerService plannerService;
    @Autowired private ProjectAffinityDetector affinityDetector;

    @When("I start an autonomous development session with requirement {string}")
    public void startAutodevWithRequirement(String requirement) throws Exception {
        stubClarifierConfident();
        stubDetectorEmpty();

        String json = objectMapper.writeValueAsString(
                Map.of("payload", requirement, "source", "FREE_FORM"));
        BrainHttpResponse response = http.postJson("/api/v1/autodev/start", json);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());

        JsonNode body = objectMapper.readTree(response.body());
        if (body.has("sessionId")) {
            state.setCapturedSessionId(body.get("sessionId").asText());
        }
    }

    @When("I start an autonomous development session with no payload")
    public void startAutodevWithNoPayload() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("source", "FREE_FORM"));
        BrainHttpResponse response = http.postJson("/api/v1/autodev/start", json);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @Given("an autonomous session exists with pending clarification")
    public void autodevSessionWithPendingClarification() {
        stubClarifierConfident();
        ClarificationSession session = createSession(SessionStatus.CLARIFYING);
        session.setRounds(List.of(Map.of("questions", List.of(Map.of("text", "Sync or async?", "options", List.of("Synchronous", "Asynchronous"))), "answers", "")));
        sessionRepository.save(session);
        state.setCapturedSessionId(session.getId().toString());
    }

    @Given("an autonomous session exists in CLARIFYING status")
    public void autodevSessionInClarifyingStatus() {
        stubClarifierConfident();
        ClarificationSession session = createSession(SessionStatus.CLARIFYING);
        sessionRepository.save(session);
        state.setCapturedSessionId(session.getId().toString());
    }

    @Given("an autonomous session exists in PLANNED status with a final plan")
    public void autodevSessionInPlannedStatus() {
        ClarificationSession session = createSession(SessionStatus.PLANNED);
        session.setFinalPlan(Map.of("projects", List.of(Map.of(
                "projectId", session.getProjectId(),
                "status", "OK",
                "plan", Map.of("affectedFiles", List.of(), "steps", List.of())
        ))));
        sessionRepository.save(session);
        state.setCapturedSessionId(session.getId().toString());
    }

    @When("I submit answers {string} to the session")
    public void submitAnswers(String answers) throws Exception {
        stubClarifierConfident();
        String json = objectMapper.writeValueAsString(
                Map.of("sessionId", state.getCapturedSessionId(), "answers", answers));
        BrainHttpResponse response = http.postJson("/api/v1/autodev/clarify", json);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @When("I request plan generation for the session")
    public void requestPlan() throws Exception {
        when(plannerService.generateMultiRepoPlan(anyList(), anyString(), anyList()))
                .thenReturn("{\"multiRepo\":true,\"projects\":[]}");

        String json = objectMapper.writeValueAsString(
                Map.of("sessionId", state.getCapturedSessionId()));
        BrainHttpResponse response = http.postJson("/api/v1/autodev/plan", json);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @When("I request execution for the session")
    public void requestExecution() throws Exception {
        String json = objectMapper.writeValueAsString(
                Map.of("sessionId", state.getCapturedSessionId()));
        BrainHttpResponse response = http.postJson("/api/v1/autodev/execute", json);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    private ClarificationSession createSession(SessionStatus status) {
        ClarificationSession session = new ClarificationSession();
        session.setProjectId("proj-autodev-test");
        session.setRequirement("BDD test requirement");
        session.setStatus(status);
        session.setRounds(new ArrayList<>());
        session.setAffectedProjects(List.of(Map.of(
                "projectId", "proj-autodev-test", "confidence", 0.9, "rationale", "test")));
        return sessionRepository.save(session);
    }

    private void stubClarifierConfident() {
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(ClarificationResponse.fromRawJson("""
                        {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                        """));
    }

    private void stubDetectorEmpty() {
        lenient().when(affinityDetector.detect(anyString())).thenReturn(List.of());
    }
}
