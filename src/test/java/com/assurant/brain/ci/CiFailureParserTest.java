package com.assurant.brain.ci;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CiFailureParser")
class CiFailureParserTest {

    private ChatModel chatModel;
    private CiFailureParser parser;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        parser = new CiFailureParser(chatModel, new ObjectMapper(), tracker, props);
    }

    @Test
    @DisplayName("parse extracts failures from CI logs")
    void parseHappyPath() {
        mockChatResponse("{\"failures\": [\"TestFoo failed: expected 1 got 0\"], \"summary\": \"1 failure\", \"category\": \"TEST_FAILURE\"}");

        CiFailureParser.FailureAnalysis result = parser.parse("ERROR: TestFoo failed...");

        assertThat(result.failures()).containsExactly("TestFoo failed: expected 1 got 0");
        assertThat(result.category()).isEqualTo("TEST_FAILURE");
    }

    @Test
    @DisplayName("parse handles markdown fences")
    void parseStripsFences() {
        mockChatResponse("```json\n{\"failures\": [\"compile error\"], \"summary\": \"1 error\", \"category\": \"COMPILE_ERROR\"}\n```");

        CiFailureParser.FailureAnalysis result = parser.parse("logs");
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    @DisplayName("parse handles invalid JSON gracefully")
    void parseInvalidJson() {
        mockChatResponse("this is not json");

        CiFailureParser.FailureAnalysis result = parser.parse("logs");
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0)).contains("Unable to parse CI logs");
    }

    @Test
    @DisplayName("parse truncates very long logs")
    void parseTruncatesLong() {
        mockChatResponse("{\"failures\": [], \"summary\": \"clean\", \"category\": \"MIXED\"}");

        String longLogs = "X".repeat(20000);
        CiFailureParser.FailureAnalysis result = parser.parse(longLogs);

        assertThat(result.failures()).isEmpty();
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
