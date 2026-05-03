package com.assurant.brain.service;

import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClarifierService — full analyze flow")
class ClarifierServiceFullTest {

    private ChatModel chatModel;
    private ProjectNodeRepository projectNodeRepository;
    private ClarifierService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        projectNodeRepository = mock(ProjectNodeRepository.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var railChain = mock(com.assurant.brain.guardrail.RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(i -> {
            com.assurant.brain.guardrail.RailContext ctx = i.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        when(railChain.applyPostLlm(any())).thenAnswer(i -> {
            com.assurant.brain.guardrail.RailContext ctx = i.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        service = new ClarifierService(chatModel, projectNodeRepository, tracker, props, railChain);
    }

    @Test
    @DisplayName("analyze returns confident response when LLM scores are high (after round 1)")
    void analyzeConfidentResponse() {
        mockProjectNodes("proj-1", "spring-boot");
        mockChatResponse("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "clear" },
                    "what":  { "score": 0.8, "summary": "clear" },
                    "where": { "score": 0.8, "summary": "clear" },
                    "how":   { "score": 0.8, "summary": "enough" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        List<Map<String, Object>> previousRounds = List.of(
                Map.of("questions", "Which module?", "answers", "API gateway"));
        ClarificationResponse result = service.analyze("proj-1", "Add rate limiting", previousRounds);

        assertThat(result.isConfident()).isTrue();
        assertThat(result.getQuestions()).isEmpty();
    }

    @Test
    @DisplayName("analyze returns not-confident when LLM returns low scores")
    void analyzeNotConfidentResponse() {
        mockProjectNodes("proj-1", null);
        mockChatResponse("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.3, "summary": "unclear" },
                    "what":  { "score": 0.5, "summary": "vague" },
                    "where": { "score": 0.2, "summary": "unknown" },
                    "how":   { "score": 0.4, "summary": "no preference" }
                  },
                  "unknownReferences": [],
                  "questions": ["What module should this go in?", "What is the rate limit?"]
                }
                """);

        ClarificationResponse result = service.analyze("proj-1", "Add rate limiting", List.of());

        assertThat(result.isConfident()).isFalse();
        assertThat(result.getQuestions()).hasSize(2);
    }

    @Test
    @DisplayName("analyze strips markdown fences from LLM response")
    void analyzeStripsMarkdownFences() {
        mockProjectNodes("proj-1", null);
        mockChatResponse("""
                ```json
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.9, "summary": "ok" },
                    "how":   { "score": 0.9, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                ```
                """);

        List<Map<String, Object>> previousRounds = List.of(
                Map.of("questions", "Q1", "answers", "A1"));
        ClarificationResponse result = service.analyze("proj-1", "Add feature", previousRounds);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    @DisplayName("analyze strips bare triple-backtick fences")
    void analyzeStripsBareBackticks() {
        mockProjectNodes("proj-1", null);
        mockChatResponse("""
                ```
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.8, "summary": "ok" },
                    "what":  { "score": 0.8, "summary": "ok" },
                    "where": { "score": 0.8, "summary": "ok" },
                    "how":   { "score": 0.8, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                ```
                """);

        List<Map<String, Object>> previousRounds = List.of(
                Map.of("questions", "Q1", "answers", "A1"));
        ClarificationResponse result = service.analyze("proj-1", "Fix bug", previousRounds);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    @DisplayName("analyze with previous rounds builds conversation context")
    void analyzeWithPreviousRounds() {
        mockProjectNodes("proj-1", "spring-boot");
        mockChatResponse("""
                {
                  "confident": true,
                  "dimensions": {
                    "why":   { "score": 0.9, "summary": "ok" },
                    "what":  { "score": 0.9, "summary": "ok" },
                    "where": { "score": 0.9, "summary": "ok" },
                    "how":   { "score": 0.9, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": []
                }
                """);

        List<Map<String, Object>> rounds = List.of(
                Map.of("questions", "Which DB?", "answers", "PostgreSQL")
        );

        ClarificationResponse result = service.analyze("proj-1", "Add caching", rounds);

        assertThat(result.isConfident()).isTrue();
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("analyze with empty known projects lists 'none'")
    void analyzeEmptyProjects() {
        when(projectNodeRepository.findAll()).thenReturn(List.of());
        mockChatResponse("""
                {
                  "confident": false,
                  "dimensions": {
                    "why":   { "score": 0.5, "summary": "ok" },
                    "what":  { "score": 0.5, "summary": "ok" },
                    "where": { "score": 0.5, "summary": "ok" },
                    "how":   { "score": 0.5, "summary": "ok" }
                  },
                  "unknownReferences": [],
                  "questions": ["Which project?"]
                }
                """);

        ClarificationResponse result = service.analyze("proj-1", "Add feature", List.of());

        assertThat(result.isConfident()).isFalse();
    }

    @Test
    @DisplayName("analyze handles malformed JSON from LLM gracefully")
    void analyzeMalformedJson() {
        mockProjectNodes("proj-1", null);
        mockChatResponse("This is not valid JSON at all");

        ClarificationResponse result = service.analyze("proj-1", "Add feature", List.of());

        assertThat(result.isConfident()).isFalse();
        assertThat(result.getQuestions()).isNotEmpty();
    }

    private void mockProjectNodes(String projectId, String framework) {
        ProjectNode node = new ProjectNode();
        node.setId(projectId);
        node.setFramework(framework);
        when(projectNodeRepository.findAll()).thenReturn(List.of(node));
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage output =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
