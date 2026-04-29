package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AiderDiffFormatter")
class AiderDiffFormatterTest {

    private ChatModel chatModel;
    private TokenUsageTracker tracker;
    private RailChain railChain;
    private AiderDiffFormatter formatter;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        tracker = mock(TokenUsageTracker.class);
        railChain = mock(RailChain.class);
        when(railChain.applyPreLlm(any(RailContext.class))).thenAnswer(inv -> {
            RailContext ctx = inv.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        var llm = new BrainProperties.Llm("plan-model", "extract-model", "ollama", 4.0);
        var props = new BrainProperties(llm, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        formatter = new AiderDiffFormatter(chatModel, tracker, props, railChain);
    }

    @Test
    @DisplayName("generateBlocks invokes RailChain.applyPreLlm and returns LLM output")
    void invokesRailChain() {
        mockChatResponse("path/F.java\n<<<<<<< SEARCH\nx\n=======\ny\n>>>>>>> REPLACE\n");

        String result = formatter.generateBlocks("class F { int x; }", "path/F.java",
                "Rename x to y", "Use Lombok");

        assertThat(result).contains("<<<<<<< SEARCH");
        verify(railChain, times(1)).applyPreLlm(any(RailContext.class));
    }

    @Test
    @DisplayName("generateBlocks tracks tokens with CODE_GENERATE op + plan-model name")
    void tracksTokens() {
        mockChatResponse("path/F.java\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE");

        formatter.generateBlocks("a", "path/F.java", "swap a to b", "");

        verify(tracker).track(eq("AiderDiffFormatter"), eq(LlmOperation.CODE_GENERATE),
                any(), anyString(), anyString(), anyLong(), anyBoolean(), eq("plan-model"));
    }

    @Test
    @DisplayName("generateBlocks passes file/desc/conventions into the user prompt")
    void promptIncludesAllInputs() {
        mockChatResponse("ok");

        formatter.generateBlocks("EXISTING_CODE", "src/Foo.java",
                "DESC_TEXT", "CONV_TEXT");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("src/Foo.java")
                .contains("EXISTING_CODE")
                .contains("DESC_TEXT")
                .contains("CONV_TEXT");
    }

    @Test
    @DisplayName("falls back to model name 'unknown' when LLM config is null")
    void unknownModelWhenLlmNull() {
        var propsNoLlm = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var f = new AiderDiffFormatter(chatModel, tracker, propsNoLlm, railChain);
        mockChatResponse("ok");

        f.generateBlocks("a", "path/F.java", "desc", "");

        verify(tracker).track(anyString(), any(LlmOperation.class), any(),
                anyString(), anyString(), anyLong(), anyBoolean(), eq("unknown"));
    }

    private void mockChatResponse(String text) {
        AssistantMessage msg = mock(AssistantMessage.class);
        when(msg.getText()).thenReturn(text);
        Generation gen = mock(Generation.class);
        when(gen.getOutput()).thenReturn(msg);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(gen);
        when(chatModel.call(any(Prompt.class))).thenReturn(resp);
    }
}
