package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.monitor.TokenUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DiffGenerator")
class DiffGeneratorTest {

    private ChatModel chatModel;
    private TokenUsageTracker tokenUsageTracker;
    private BrainProperties brainProperties;
    private DiffGenerator diffGenerator;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        tokenUsageTracker = mock(TokenUsageTracker.class);
        brainProperties = mock(BrainProperties.class);
        when(brainProperties.llm()).thenReturn(null);
        diffGenerator = new DiffGenerator(chatModel, tokenUsageTracker, brainProperties);
    }

    @Test
    @DisplayName("isSmallChange returns false for null plan")
    void isSmallChangeNullPlan() {
        assertThat(diffGenerator.isSmallChange(null)).isFalse();
    }

    @Test
    @DisplayName("isSmallChange returns false when plan has no affectedFiles key")
    void isSmallChangeMissingKeys() {
        assertThat(diffGenerator.isSmallChange(Map.of())).isFalse();
    }

    @Test
    @DisplayName("isSmallChange returns true for 1-file, 1-step plan")
    void isSmallChangeOneFile() {
        Map<String, Object> plan = Map.of(
                "affectedFiles", List.of("Foo.java"),
                "steps", List.of("Add method")
        );
        assertThat(diffGenerator.isSmallChange(plan)).isTrue();
    }

    @Test
    @DisplayName("isSmallChange returns true for 3-file plan at boundary")
    void isSmallChangeThreeFiles() {
        Map<String, Object> plan = Map.of(
                "affectedFiles", List.of("A.java", "B.java", "C.java"),
                "steps", List.of("Step1", "Step2", "Step3")
        );
        assertThat(diffGenerator.isSmallChange(plan)).isTrue();
    }

    @Test
    @DisplayName("isSmallChange returns false for 4-file plan")
    void isNotSmallChangeFourFiles() {
        Map<String, Object> plan = Map.of(
                "affectedFiles", List.of("A.java", "B.java", "C.java", "D.java"),
                "steps", List.of("Step1", "Step2", "Step3", "Step4")
        );
        assertThat(diffGenerator.isSmallChange(plan)).isFalse();
    }

    @Test
    @DisplayName("generateDiff returns LLM output as-is")
    void generateDiffReturnsDiff() {
        String expectedDiff = "@@ -1,1 +1,2 @@\n line\n+new line";

        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn(expectedDiff);

        String result = diffGenerator.generateDiff("line1", "src/Foo.java", "add a line", "convention: use Log4j2");
        assertThat(result).isEqualTo(expectedDiff);
    }
}
