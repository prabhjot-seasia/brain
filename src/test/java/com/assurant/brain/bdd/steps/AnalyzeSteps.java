package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class AnalyzeSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private JdbcTemplate jdbcTemplate;
    @Autowired private SharedTestState state;

    @Autowired private ClarifierService clarifierService;
    @Autowired private PlannerService plannerService;

    @Before
    public void resetState() {
        state.reset();
    }

    @Given("the Brain API is running with mocked LLM services")
    public void theBrainApiIsRunning() {
    }

    @Given("the clarifier will respond as not confident with questions:")
    public void clarifierNotConfidentWithQuestions(DataTable table) {
        List<String> questions = table.asList();
        ClarificationResponse response = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.3, "summary": "unclear" },
                    "what":  { "score": 0.3, "summary": "unclear" },
                    "where": { "score": 0.0, "summary": "unknown" },
                    "how":   { "score": 0.0, "summary": "unknown" }
                  },
                  "unknownReferences": [],
                  "questions": %s
                }
                """.formatted(toJsonArray(questions)));
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(response);
    }

    @Given("the clarifier will respond as not confident with unknown references:")
    public void clarifierNotConfidentWithUnknownRefs(DataTable table) {
        List<String> refs = table.asList();
        ClarificationResponse response = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.5, "summary": "partial" },
                    "what":  { "score": 0.5, "summary": "partial" },
                    "where": { "score": 0.0, "summary": "unknown" },
                    "how":   { "score": 0.0, "summary": "unknown" }
                  },
                  "unknownReferences": %s,
                  "questions": ["Please clarify the unknown references above."]
                }
                """.formatted(toJsonArray(refs)));
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(response);
    }

    @Given("the clarifier will respond as confident")
    public void clarifierConfident() {
        ClarificationResponse response = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 1.0, "summary": "fully understood" },
                    "what":  { "score": 1.0, "summary": "fully scoped" },
                    "where": { "score": 1.0, "summary": "identified" },
                    "how":   { "score": 1.0, "summary": "approach clear" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(response);
    }

    @Given("the planner will generate a valid implementation plan")
    public void plannerGeneratesPlan() {
        String planJson = """
                {
                  "requirement": "Test requirement",
                  "understanding": { "why": "test", "what": "test", "where": "test", "how": "test" },
                  "affectedFiles": [{ "path": "src/main/java/Service.java", "reason": "entry point", "confidence": 1.0 }],
                  "steps": [{ "order": 1, "description": "Implement change", "convention": "Constructor injection [source: CONTRIBUTING.md]", "files": ["Service.java"] }],
                  "risks": [],
                  "conventionsApplied": [{ "rule": "Use constructor injection", "source": "CONTRIBUTING.md" }],
                  "assumptions": []
                }
                """;
        when(plannerService.generatePlan(anyString(), anyString(), anyList()))
                .thenReturn(planJson);
    }

    @When("I submit an analyze request for project {string} with requirement {string}")
    public void submitAnalyzeRequest(String projectId, String requirement) {
        String body = """
                { "projectId": "%s", "requirement": "%s" }
                """.formatted(projectId, requirement);
        captureResponse(http.postJson("/api/v1/analyze", body));
    }

    @When("I submit an analyze request for project {string} with requirement {string} using the captured session and answers {string}")
    public void submitAnalyzeWithSessionAndAnswers(String projectId, String requirement, String answers) {
        String body = """
                { "projectId": "%s", "requirement": "%s", "sessionId": "%s", "answers": "%s" }
                """.formatted(projectId, requirement, state.getCapturedSessionId(), answers);
        captureResponse(http.postJson("/api/v1/analyze", body));
    }

    @When("I submit an analyze request with missing projectId")
    public void submitAnalyzeWithMissingProjectId() {
        captureResponse(http.postJson("/api/v1/analyze", """
                { "requirement": "Add logging" }
                """));
    }

    @When("I submit an analyze request with missing requirement")
    public void submitAnalyzeWithMissingRequirement() {
        captureResponse(http.postJson("/api/v1/analyze", """
                { "projectId": "proj-payments" }
                """));
    }

    @When("I submit an analyze request for project {string} with requirement {string} and invalid session {string}")
    public void submitAnalyzeWithInvalidSession(String projectId, String requirement, String sessionId) {
        String body = """
                { "projectId": "%s", "requirement": "%s", "sessionId": "%s" }
                """.formatted(projectId, requirement, sessionId);
        captureResponse(http.postJson("/api/v1/analyze", body));
    }

    @When("I submit an analyze request for project {string} with blank requirement")
    public void submitAnalyzeWithBlankRequirement(String projectId) {
        String body = """
                { "projectId": "%s", "requirement": "   " }
                """.formatted(projectId);
        captureResponse(http.postJson("/api/v1/analyze", body));
    }

    private void captureResponse(BrainHttpResponse response) {
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @Then("the response status is {int}")
    public void responseStatusIs(int expectedStatus) {
        assertThat(state.getResponseStatus()).isEqualTo(expectedStatus);
    }

    @Then("the response field {string} is false")
    public void responseFieldIsFalse(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get(field).asBoolean()).isFalse();
    }

    @Then("the response field {string} is true")
    public void responseFieldIsTrue(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get(field).asBoolean()).isTrue();
    }

    @Then("the response field {string} is {string}")
    public void responseFieldEquals(String field, String expected) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get(field).asText()).isEqualTo(expected);
    }

    @And("the response contains a non-empty {string}")
    public void responseContainsNonEmpty(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.has(field)).isTrue();
        assertThat(node.get(field).asText()).isNotBlank();
    }

    @And("the response contains {int} questions")
    public void responseContainsNQuestions(int count) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get("questions").size()).isEqualTo(count);
    }

    @And("the response question {int} is {string}")
    public void responseQuestionIs(int index, String expected) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        JsonNode question = node.get("questions").get(index - 1);
        String text = question.isObject() ? question.get("text").asText() : question.asText();
        assertThat(text).isEqualTo(expected);
    }

    @And("the response contains {int} unknown references")
    public void responseContainsNUnknownRefs(int count) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get("unknownReferences").size()).isEqualTo(count);
    }

    @And("the response unknown reference {int} is {string}")
    public void responseUnknownRefIs(int index, String expected) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get("unknownReferences").get(index - 1).asText()).isEqualTo(expected);
    }

    @And("I capture the {string} from the response")
    public void captureField(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        if ("sessionId".equals(field)) {
            state.setCapturedSessionId(node.get(field).asText());
        }
    }

    @And("the session is marked as {string} in the database")
    public void sessionIsMarkedInDb(String expectedStatus) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clarification_sessions WHERE id = ?::uuid AND status = ?",
                Integer.class, state.getCapturedSessionId(), expectedStatus);
        assertThat(count).isEqualTo(1);
    }

    @And("the response body contains {string}")
    public void responseBodyContains(String expected) {
        assertThat(state.getResponseBody()).contains(expected);
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
