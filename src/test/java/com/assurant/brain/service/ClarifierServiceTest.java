package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClarifierService server-side confidence guard")
class ClarifierServiceTest {

    private final ClarifierService service = new ClarifierService(
            Mockito.mock(ChatModel.class),
            Mockito.mock(ProjectNodeRepository.class),
            Mockito.mock(com.assurant.brain.monitor.TokenUsageTracker.class),
            new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    new BrainProperties.Clarifier(0.75, 0.5, 3, 5, 5), null, null, null, null, null),
            Mockito.mock(com.assurant.brain.guardrail.RailChain.class)
    );

    @Test
    @DisplayName("LLM says confident=false but all four dimensions average ≥ 0.75 → server overrides to true")
    void overrideToConfident_whenAverageAboveThresholdAndAllAboveFloor() {
        ClarificationResponse llmSaysNo = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.6, "summary": "assumed" },
                    "how":   { "score": 0.8, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["q1", "q2", "q3"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llmSaysNo, 1);

        assertThat(result.isConfident())
                .as("avg=0.8, all dimensions ≥ 0.5 → server should mark confident")
                .isTrue();
    }

    @Test
    @DisplayName("LLM says confident=true but one dimension is below the 0.5 floor → server overrides to false")
    void overrideToNotConfident_whenAnyDimensionBelowFloor() {
        ClarificationResponse llmSaysYes = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 1.0, "summary": "ok" },
                    "what":  { "score": 1.0, "summary": "ok" },
                    "where": { "score": 0.3, "summary": "no idea" },
                    "how":   { "score": 1.0, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llmSaysYes, 1);

        assertThat(result.isConfident())
                .as("where=0.3 is below the 0.5 floor → server should mark not confident")
                .isFalse();
    }

    @Test
    @DisplayName("All dimensions above floor but average below 0.75 → not confident")
    void notConfident_whenAverageBelowThresholdEvenIfAllAboveFloor() {
        ClarificationResponse llmSaysYes = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.6, "summary": "thin" },
                    "what":  { "score": 0.6, "summary": "thin" },
                    "where": { "score": 0.6, "summary": "thin" },
                    "how":   { "score": 0.6, "summary": "thin" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llmSaysYes, 1);

        assertThat(result.isConfident())
                .as("avg=0.6 < 0.75 threshold → server should mark not confident")
                .isFalse();
    }

    @Test
    @DisplayName("All dimensions perfect → confident")
    void confident_whenAllPerfect() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 1.0, "summary": "ok" },
                    "what":  { "score": 1.0, "summary": "ok" },
                    "where": { "score": 1.0, "summary": "ok" },
                    "how":   { "score": 1.0, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["q1", "q2", "q3"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 1);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    @DisplayName("unknownReferences present → always not confident even with perfect scores")
    void notConfident_whenUnknownReferencesPresent() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 1.0, "summary": "ok" },
                    "what":  { "score": 1.0, "summary": "ok" },
                    "where": { "score": 1.0, "summary": "ok" },
                    "how":   { "score": 1.0, "summary": "ok" }
                  },
                  "unknownReferences": ["mystery-service"],
                  "questions": []
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 0);
        assertThat(result.isConfident())
                .as("unknown reference is always a blocker")
                .isFalse();
    }

    @Test
    @DisplayName("Boundary case — avg=0.75 exactly at threshold and all dimensions at floor → confident")
    void confident_atExactThresholdAndFloor() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.75, "summary": "ok" },
                    "what":  { "score": 0.75, "summary": "ok" },
                    "where": { "score": 0.75, "summary": "ok" },
                    "how":   { "score": 0.75, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["q1", "q2", "q3"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 1);
        assertThat(result.isConfident())
                .as("exact threshold (0.75) and floor (0.5) should be inclusive")
                .isTrue();
    }

    @Test
    @DisplayName("First round with fewer than 3 questions → forced not-confident")
    void notConfident_firstRoundTooFewQuestions() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.9, "summary": "ok" },
                    "how":   { "score": 0.9, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["only one question"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 0);
        assertThat(result.isConfident())
                .as("first round with only 1 question (min 3) → forced not-confident")
                .isFalse();
    }

    @Test
    @DisplayName("Second round with fewer than 3 questions → no enforcement, uses threshold only")
    void confident_secondRoundFewQuestionsAllowed() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.9, "summary": "ok" },
                    "how":   { "score": 0.9, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["only one question"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 1);
        assertThat(result.isConfident())
                .as("second round: fewer than 3 questions is fine, avg=0.9 ≥ 0.75 → confident")
                .isTrue();
    }

    @Test
    @DisplayName("First round with 3+ questions and high scores → confident")
    void confident_firstRoundEnoughQuestions() {
        ClarificationResponse llm = ClarificationResponse.fromRawJson("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.9, "summary": "ok" },
                    "how":   { "score": 0.9, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["q1", "q2", "q3"]
                }
                """);

        ClarificationResponse result = service.enforceServerSideConfidence(llm, 0);
        assertThat(result.isConfident())
                .as("first round with 3 questions and avg=0.9 → confident")
                .isTrue();
    }
}
