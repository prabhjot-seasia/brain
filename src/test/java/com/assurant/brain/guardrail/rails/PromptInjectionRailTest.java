package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.assurant.brain.guardrail.classifier.PromptInjectionClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PromptInjectionRail")
class PromptInjectionRailTest {

    private PromptInjectionClassifier classifier;
    private PromptInjectionRail rail;

    @BeforeEach
    void setup() {
        classifier = mock(PromptInjectionClassifier.class);
        rail = new PromptInjectionRail(classifier);
    }

    @Test
    @DisplayName("blocks when classifier reports injection")
    void blocksInjection() {
        when(classifier.classify("bad input")).thenReturn(
                PromptInjectionClassifier.ClassificationResult.injected(0.9, List.of("pattern1")));
        RailResult result = rail.apply(RailContext.preLlm("p", "svc", "bad input"));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
        assertThat(result.violations()).contains("pattern1");
    }

    @Test
    @DisplayName("passes when classifier reports safe")
    void passesSafe() {
        when(classifier.classify("good input")).thenReturn(
                PromptInjectionClassifier.ClassificationResult.safe());
        RailResult result = rail.apply(RailContext.preLlm("p", "svc", "good input"));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("passes on null or blank input")
    void passesOnBlank() {
        RailResult result = rail.apply(RailContext.preLlm("p", "svc", ""));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }
}
