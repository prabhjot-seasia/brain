package com.assurant.brain.observability;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.enums.RailType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrainMetrics.recordGuardrailOutcome")
class BrainMetricsGuardrailTest {

    @Test
    @DisplayName("records tagged counter per rail and decision")
    void recordsTaggedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BrainMetrics metrics = new BrainMetrics(registry);

        metrics.recordGuardrailOutcome(RailType.INJECTION, RailDecision.BLOCK);
        metrics.recordGuardrailOutcome(RailType.INJECTION, RailDecision.BLOCK);
        metrics.recordGuardrailOutcome(RailType.PII, RailDecision.MODIFY);
        metrics.recordGuardrailOutcome(RailType.LENGTH, RailDecision.PASS);

        assertThat(registry.counter("brain.guardrail.outcome", "rail", "INJECTION", "decision", "BLOCK").count()).isEqualTo(2.0);
        assertThat(registry.counter("brain.guardrail.outcome", "rail", "PII", "decision", "MODIFY").count()).isEqualTo(1.0);
        assertThat(registry.counter("brain.guardrail.outcome", "rail", "LENGTH", "decision", "PASS").count()).isEqualTo(1.0);
    }
}
