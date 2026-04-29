package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.InjectionClassifierType;
import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputLengthRail")
class OutputLengthRailTest {

    private OutputLengthRail build(int max) {
        var guardrails = new BrainProperties.Guardrails(20000, 50000, max, false, true, InjectionClassifierType.REGEX);
        return new OutputLengthRail(
                new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, guardrails, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("passes when output within limit")
    void passesWithin() {
        RailResult result = build(100).apply(RailContext.postLlm("p", "svc", "short", Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("blocks when output exceeds limit")
    void blocksOver() {
        RailResult result = build(5).apply(RailContext.postLlm("p", "svc", "this is too long", Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
    }
}
