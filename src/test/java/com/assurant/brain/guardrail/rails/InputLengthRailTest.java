package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.InjectionClassifierType;
import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InputLengthRail")
class InputLengthRailTest {

    private BrainProperties props(int maxInput) {
        var guardrails = new BrainProperties.Guardrails(maxInput, 50000, 30000, false, true, InjectionClassifierType.REGEX);
        return new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, guardrails, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("passes when input within limit")
    void passesWithinLimit() {
        InputLengthRail rail = new InputLengthRail(props(100));
        RailResult result = rail.apply(RailContext.preLlm("p", "svc", "short input"));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("blocks when input exceeds limit")
    void blocksOverLimit() {
        InputLengthRail rail = new InputLengthRail(props(10));
        RailResult result = rail.apply(RailContext.preLlm("p", "svc", "this is a much longer input than allowed"));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    @DisplayName("passes on null input")
    void passesOnNull() {
        InputLengthRail rail = new InputLengthRail(props(10));
        RailContext ctx = new RailContext(com.assurant.brain.enums.RailPhase.PRE_LLM, "p", "svc", null, null, null, null, java.util.Map.of());
        RailResult result = rail.apply(ctx);
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }
}
