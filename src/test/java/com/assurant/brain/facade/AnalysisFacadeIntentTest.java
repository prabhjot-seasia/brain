package com.assurant.brain.facade;

import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("AnalysisFacade — intent classification")
class AnalysisFacadeIntentTest {

    private final AnalysisFacade facade = new AnalysisFacade(
            mock(ClarifierService.class),
            mock(PlannerService.class),
            mock(ClarificationSessionRepository.class),
            new ObjectMapper(),
            mock(com.assurant.brain.service.ProjectAffinityDetector.class),
            new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
            mock(com.assurant.brain.jobs.AsyncJobService.class)
    );

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain this project",
            "describe the auth flow",
            "What is the payment service?",
            "How does ingestion work?",
            "Tell me about the conventions",
            "Summarize the architecture",
            "Walk me through the data flow",
            "Give me an overview of this codebase",
            "What's in this project?",
            "Show me the main components"
    })
    @DisplayName("EXPLAIN intent detected for read-only requirements")
    void explainIntent(String requirement) {
        assertThat(facade.classifyIntent(requirement))
                .isEqualTo(AnalysisFacade.RequirementIntent.EXPLAIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Add a GSMA Device Check validator",
            "Implement rate limiting on /api/validate",
            "Fix the null pointer in IngestionService",
            "Refactor the clarifier to use streaming",
            "Create a new REST endpoint for bulk import",
            "Update the JWT validation logic",
            "Remove the deprecated auth middleware"
    })
    @DisplayName("IMPLEMENT intent detected for change requirements")
    void implementIntent(String requirement) {
        assertThat(facade.classifyIntent(requirement))
                .isEqualTo(AnalysisFacade.RequirementIntent.IMPLEMENT);
    }

    @Test
    @DisplayName("null requirement defaults to IMPLEMENT")
    void nullRequirement() {
        assertThat(facade.classifyIntent(null))
                .isEqualTo(AnalysisFacade.RequirementIntent.IMPLEMENT);
    }
}
