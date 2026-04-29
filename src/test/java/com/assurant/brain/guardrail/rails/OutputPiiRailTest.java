package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputPiiRail")
class OutputPiiRailTest {

    private final OutputPiiRail rail = new OutputPiiRail();

    @Test
    @DisplayName("blocks when LLM output leaks SSN")
    void blocksSsnLeak() {
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", "User SSN: 123-45-6789", Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
    }

    @Test
    @DisplayName("passes clean output")
    void passesClean() {
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", "Clean response here", Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("passes on null output")
    void passesNull() {
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", null, Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }
}
