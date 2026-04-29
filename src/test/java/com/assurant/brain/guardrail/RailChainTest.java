package com.assurant.brain.guardrail;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.exceptions.RailBlockedException;
import com.assurant.brain.observability.BrainMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RailChain")
class RailChainTest {

    private final BrainMetrics metrics = mock(BrainMetrics.class);

    private Rail rail(RailPhase phase, int priority, RailType type, RailResult result) {
        return new Rail() {
            @Override public RailResult apply(RailContext context) { return result; }
            @Override public RailPhase phase() { return phase; }
            @Override public int priority() { return priority; }
            @Override public RailType type() { return type; }
        };
    }

    @Test
    @DisplayName("applies rails in ascending priority order")
    void priorityOrder() {
        Rail a = rail(RailPhase.PRE_LLM, 20, RailType.INJECTION, RailResult.pass(RailType.INJECTION));
        Rail b = rail(RailPhase.PRE_LLM, 10, RailType.LENGTH, RailResult.pass(RailType.LENGTH));
        RailChain chain = new RailChain(List.of(a, b), metrics);

        RailChain.ChainResult result = chain.applyPreLlm(RailContext.preLlm("p", "svc", "input"));
        assertThat(result.rails()).hasSize(2);
        assertThat(result.rails().get(0).rail()).isEqualTo(RailType.LENGTH);
        assertThat(result.rails().get(1).rail()).isEqualTo(RailType.INJECTION);
    }

    @Test
    @DisplayName("short-circuits on BLOCK decision")
    void shortCircuitOnBlock() {
        Rail blocker = rail(RailPhase.PRE_LLM, 10, RailType.INJECTION,
                RailResult.block(RailType.INJECTION, "injection detected"));
        Rail after = rail(RailPhase.PRE_LLM, 20, RailType.LENGTH, RailResult.pass(RailType.LENGTH));
        RailChain chain = new RailChain(List.of(blocker, after), metrics);

        assertThatThrownBy(() -> chain.applyPreLlm(RailContext.preLlm("p", "svc", "bad")))
                .isInstanceOf(RailBlockedException.class);
    }

    @Test
    @DisplayName("MODIFY decision propagates sanitized input to next rail")
    void modifyPropagates() {
        Rail modifier = rail(RailPhase.PRE_LLM, 10, RailType.PII,
                RailResult.modify(RailType.PII, "sanitized", List.of("[PII-SSN]")));
        Rail passthrough = rail(RailPhase.PRE_LLM, 20, RailType.LENGTH, RailResult.pass(RailType.LENGTH));
        RailChain chain = new RailChain(List.of(modifier, passthrough), metrics);

        RailChain.ChainResult result = chain.applyPreLlm(RailContext.preLlm("p", "svc", "raw"));
        assertThat(result.sanitized()).isEqualTo("sanitized");
    }

    @Test
    @DisplayName("separates pre-LLM and post-LLM rails")
    void separatesPhases() {
        Rail pre = rail(RailPhase.PRE_LLM, 10, RailType.LENGTH, RailResult.pass(RailType.LENGTH));
        Rail post = rail(RailPhase.POST_LLM, 10, RailType.SCHEMA, RailResult.pass(RailType.SCHEMA));
        RailChain chain = new RailChain(List.of(pre, post), metrics);

        RailChain.ChainResult preResult = chain.applyPreLlm(RailContext.preLlm("p", "svc", "in"));
        assertThat(preResult.rails()).hasSize(1);
        assertThat(preResult.rails().get(0).rail()).isEqualTo(RailType.LENGTH);

        RailChain.ChainResult postResult = chain.applyPostLlm(RailContext.postLlm("p", "svc", "out", java.util.Map.of()));
        assertThat(postResult.rails()).hasSize(1);
        assertThat(postResult.rails().get(0).rail()).isEqualTo(RailType.SCHEMA);
    }

    @Test
    @DisplayName("records metrics for each rail outcome")
    void recordsMetrics() {
        Rail a = rail(RailPhase.PRE_LLM, 10, RailType.LENGTH, RailResult.pass(RailType.LENGTH));
        RailChain chain = new RailChain(List.of(a), metrics);
        chain.applyPreLlm(RailContext.preLlm("p", "svc", "in"));
        verify(metrics).recordGuardrailOutcome(RailType.LENGTH, RailDecision.PASS);
    }
}
