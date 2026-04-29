package com.assurant.brain.rest.v1.analyze;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("POST /api/v1/analyze — BDD Integration Tests")
class AnalyzeFlowIT extends BrainApplicationTests {

    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("""
            Scenario: Vague requirement triggers clarifying questions
              Given  a developer submits an under-specified requirement
              When   POST /api/v1/analyze is called for the first time
              Then   the response contains planReady=false with clarifying questions
              And    a session ID is returned for the follow-up round
            """)
    void givenVagueRequirement_whenFirstAnalyzeCall_thenClarifyingQuestionsReturned()
            throws Exception {

        ClarificationResponse notConfident = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.2, "summary": "no business context given" },
                    "what":  { "score": 0.3, "summary": "scope unclear" },
                    "where": { "score": 0.0, "summary": "no project or module specified" },
                    "how":   { "score": 0.0, "summary": "no technical constraints" }
                  },
                  "unknownReferences": [],
                  "questions": [
                    "What is the business reason for adding logging?",
                    "Which service or module should be updated?"
                  ]
                }
                """);

        when(clarifierService.analyze(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(notConfident);

        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":   "proj-payments",
                                  "requirement": "Add some logging"
                                }
                                """))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(false))
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.questions").isArray())
                .andExpect(jsonPath("$.questions", hasSize(2)))
                .andExpect(jsonPath("$.questions[0].text")
                        .value("What is the business reason for adding logging?"))
                .andExpect(jsonPath("$.questions[1].text")
                        .value("Which service or module should be updated?"));
    }

    @Test
    @DisplayName("""
            Scenario: Developer answers questions → implementation plan is generated
              Given  a clarification session was started in a prior round
              And    the developer has answered all clarifying questions
              When   POST /api/v1/analyze is called again with the session ID + answers
              Then   the response contains planReady=true with a structured plan
              And    the session is persisted as 'complete' in the database
            """)
    void givenAnsweredSession_whenSecondAnalyzeCall_thenPlanIsReturned() throws Exception {

        ClarificationResponse round1 = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.5, "summary": "partial context" },
                    "what":  { "score": 0.5, "summary": "partially scoped" },
                    "where": { "score": 0.0, "summary": "unknown" },
                    "how":   { "score": 0.0, "summary": "unknown" }
                  },
                  "unknownReferences": [],
                  "questions": ["Which service handles payments?"]
                }
                """);

        when(clarifierService.analyze(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(round1);

        MvcResult firstCall = mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":   "proj-payments",
                                  "requirement": "Add audit logging to payment flow"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(firstCall.getResponse().getContentAsString());
        String sessionId = firstBody.get("sessionId").asText();
        assertThat(sessionId).isNotBlank();

        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 1.0, "summary": "regulatory compliance audit trail" },
                    "what":  { "score": 1.0, "summary": "add AuditService call in PaymentService.processPayment()" },
                    "where": { "score": 1.0, "summary": "PaymentService in payments-core module" },
                    "how":   { "score": 1.0, "summary": "use existing AuditService bean, no schema changes" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        String planJson = """
                {
                  "requirement": "Add audit logging to payment flow",
                  "understanding": {
                    "why":   "Regulatory compliance audit trail",
                    "what":  "Add AuditService call in PaymentService.processPayment()",
                    "where": "payments-core/PaymentService.java",
                    "how":   "Use existing AuditService bean"
                  },
                  "affectedFiles": [
                    { "path": "payments-core/src/main/java/PaymentService.java", "reason": "entry point", "confidence": 1.0 }
                  ],
                  "steps": [
                    {
                      "order": 1,
                      "description": "Inject AuditService into PaymentService via constructor",
                      "convention": "Constructor injection [source: CONTRIBUTING.md]",
                      "files": ["PaymentService.java"]
                    },
                    {
                      "order": 2,
                      "description": "Call auditService.log(event) after a successful payment commit",
                      "convention": "Log after commit [source: inferred from 12 occurrences]",
                      "files": ["PaymentService.java"]
                    }
                  ],
                  "risks": [],
                  "conventionsApplied": [
                    { "rule": "Use constructor injection", "source": "CONTRIBUTING.md" }
                  ],
                  "assumptions": []
                }
                """;

        when(clarifierService.analyze(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(confident);
        when(plannerService.generatePlan(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(planJson);

        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":   "proj-payments",
                                  "requirement": "Add audit logging to payment flow",
                                  "sessionId":   "%s",
                                  "answers":     "PaymentService in payments-core. Compliance team requires it."
                                }
                                """.formatted(sessionId)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(true))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.plan").isNotEmpty());

        Integer completedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM clarification_sessions WHERE id = ?::uuid AND status = 'COMPLETE'",
                Integer.class, sessionId);
        assertThat(completedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("""
            Scenario: 'Explain this project' bypasses clarifier and returns markdown summary
              Given  a developer asks a read-only question
              When   POST /api/v1/analyze is called
              Then   the clarifier service is NEVER invoked
              And    the response contains a markdown explanation with kind=EXPLAIN
            """)
    void explainRequirement_skipsClarifier_returnsExplanation() throws Exception {
        when(plannerService.explainProject(eq("proj-payments"), anyString()))
                .thenReturn("# Payments Project\n\nThis project handles payment processing...");

        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":   "proj-payments",
                                  "requirement": "Explain this project"
                                }
                                """))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(true))
                .andExpect(jsonPath("$.kind").value("EXPLAIN"))
                .andExpect(jsonPath("$.plan", containsString("Payments Project")))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());

        verify(clarifierService, never()).analyze(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("""
            Scenario: Server-side confidence guard overrides a stubborn LLM
              Given  the LLM returns confident=true on the first call
              When   POST /api/v1/analyze is called once
              Then   the planner is invoked and a plan is returned
            """)
    void confidenceFromGoodEnoughDimensions_promotesToPlanner() throws Exception {
        ClarificationResponse confident = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "regulatory compliance" },
                    "what":  { "score": 0.9, "summary": "add audit hook" },
                    "where": { "score": 0.5, "summary": "PaymentService — assumed" },
                    "how":   { "score": 0.8, "summary": "use existing AuditService" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        when(clarifierService.analyze(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(confident);
        when(plannerService.generatePlan(eq("proj-payments"), anyString(), anyList()))
                .thenReturn("{\"requirement\":\"...\",\"steps\":[]}");

        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":   "proj-payments",
                                  "requirement": "Add audit logging to payment flow"
                                }
                                """))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(true))
                .andExpect(jsonPath("$.forcedAfterMaxRounds").value(false))
                .andExpect(jsonPath("$.plan").isNotEmpty());
    }

    @Test
    @DisplayName("""
            Scenario: Force-promote after max clarification rounds without confidence
              Given  a session that has already had 5 rounds of clarification
              And    the clarifier still returns confident=false
              When   POST /api/v1/analyze is called with the session ID + new answers
              Then   the planner is invoked despite low confidence
              And    the response is flagged forcedAfterMaxRounds=true
            """)
    void maxRoundsReached_forcePromotesToPlanner() throws Exception {
        ClarificationResponse stillNotSure = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.2, "summary": "thin" },
                    "what":  { "score": 0.2, "summary": "thin" },
                    "where": { "score": 0.0, "summary": "unknown" },
                    "how":   { "score": 0.0, "summary": "unknown" }
                  },
                  "unknownReferences": [],
                  "questions": ["Question round 1?"]
                }
                """);
        when(clarifierService.analyze(eq("proj-payments"), anyString(), anyList()))
                .thenReturn(stillNotSure);

        MvcResult call1 = mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"proj-payments","requirement":"Add stuff"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(false))
                .andReturn();
        String sessionId = objectMapper.readTree(call1.getResponse().getContentAsString())
                .get("sessionId").asText();

        for (int i = 1; i <= 4; i++) {
            mvc.perform(post("/api/v1/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"proj-payments","requirement":"Add stuff",
                                     "sessionId":"%s","answers":"answer %d"}
                                    """.formatted(sessionId, i)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.planReady").value(false));
        }

        when(plannerService.generatePlan(eq("proj-payments"), anyString(), anyList()))
                .thenReturn("{\"requirement\":\"Add stuff\",\"steps\":[],\"forced\":true}");

        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"proj-payments","requirement":"Add stuff",
                                 "sessionId":"%s","answers":"answer 5"}
                                """.formatted(sessionId)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(true))
                .andExpect(jsonPath("$.forcedAfterMaxRounds").value(true))
                .andExpect(jsonPath("$.plan").isNotEmpty());
    }
}
