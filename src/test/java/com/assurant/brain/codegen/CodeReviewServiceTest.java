package com.assurant.brain.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CodeReviewService")
class CodeReviewServiceTest {

    private ChatModel chatModel;
    private CodeReviewService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var astValidator = new AstValidator();
        var conventionChecker = new ConventionChecker();
        var brainMetrics = mock(com.assurant.brain.observability.BrainMetrics.class);
        var railChain = mock(com.assurant.brain.guardrail.RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(i -> {
            com.assurant.brain.guardrail.RailContext ctx = i.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        when(railChain.applyPostLlm(any())).thenAnswer(i -> {
            com.assurant.brain.guardrail.RailContext ctx = i.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        service = new CodeReviewService(chatModel, new ObjectMapper(), tracker, props, astValidator, conventionChecker, brainMetrics, railChain);
    }

    @Test
    @DisplayName("review returns PASS when code is clean")
    void reviewPass() {
        mockChatResponse("{\"verdict\": \"PASS\", \"issues\": [], \"summary\": \"Clean code\"}");

        CodeReviewService.ReviewResult result = service.review(
                Map.of("src/Foo.java", "class Foo {}"), "{}");

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.summary()).isEqualTo("Clean code");
    }

    @Test
    @DisplayName("review returns FAIL with issues when problems found")
    void reviewFail() {
        mockChatResponse("{\"verdict\": \"FAIL\", \"issues\": [\"Missing import\", \"Hardcoded URL\"], \"summary\": \"2 issues\"}");

        CodeReviewService.ReviewResult result = service.review(
                Map.of("src/Foo.java", "class Foo {}"), "{}");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).containsExactly("Missing import", "Hardcoded URL");
    }

    @Test
    @DisplayName("review handles markdown fences in response")
    void reviewStripsFences() {
        mockChatResponse("```json\n{\"verdict\": \"PASS\", \"issues\": [], \"summary\": \"ok\"}\n```");

        CodeReviewService.ReviewResult result = service.review(Map.of("src/Foo.java", "package com.example;\n\npublic class Foo {}"), "{}");
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("review handles invalid JSON gracefully")
    void reviewInvalidJson() {
        mockChatResponse("This is not JSON at all");

        CodeReviewService.ReviewResult result = service.review(Map.of("src/Foo.java", "package com.example;\n\npublic class Foo {}"), "{}");

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0)).contains("Failed to parse self-review result");
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
