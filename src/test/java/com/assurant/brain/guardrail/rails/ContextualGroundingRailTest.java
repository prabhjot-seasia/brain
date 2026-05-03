package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextualGroundingRail")
class ContextualGroundingRailTest {

    private ContextualGroundingRail rail;

    private void setProvider(String provider) {
        var llm = new BrainProperties.Llm("plan", "extract", provider, 4.0);
        var props = new BrainProperties(llm, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        rail = new ContextualGroundingRail(props,
                org.mockito.Mockito.mock(com.assurant.brain.monitor.TokenUsageTracker.class));
    }

    @Test
    @DisplayName("passes when provider is not bedrock")
    void skipsWhenNotBedrock() {
        setProvider("ollama");
        RailContext ctx = RailContext.postLlm("p", "Planner", "anything",
                Map.of(ContextualGroundingRail.METADATA_GROUNDING_SOURCES, List.of("hello world")));
        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.PASS);
    }

    @Test
    @DisplayName("passes when no grounding sources provided")
    void noSources() {
        setProvider("bedrock");
        RailContext ctx = RailContext.postLlm("p", "Planner", "some response", null);
        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.PASS);
    }

    @Test
    @DisplayName("passes when output tokens are largely supported by sources")
    void groundedResponse() {
        setProvider("bedrock");
        String response = "OrderService handles checkout and payment workflow";
        RailContext ctx = RailContext.postLlm("p", "Planner", response, Map.of(
                ContextualGroundingRail.METADATA_GROUNDING_SOURCES,
                List.of("OrderService class. Handles checkout. Handles payment workflow integration."),
                ContextualGroundingRail.METADATA_GROUNDING_THRESHOLD, 0.6));
        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.PASS);
    }

    @Test
    @DisplayName("threshold parsed from string metadata value")
    void thresholdParsedFromString() {
        setProvider("bedrock");
        RailContext ctx = RailContext.postLlm("p", "Planner", "alpha beta gamma delta", Map.of(
                ContextualGroundingRail.METADATA_GROUNDING_SOURCES,
                List.of("alpha beta gamma delta epsilon"),
                ContextualGroundingRail.METADATA_GROUNDING_THRESHOLD, "0.5"));

        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.PASS);
    }

    @Test
    @DisplayName("invalid threshold string falls back to default 0.7")
    void invalidThresholdStringFallsBackToDefault() {
        setProvider("bedrock");
        RailContext ctx = RailContext.postLlm("p", "Planner",
                "alpha beta gamma delta epsilon zeta theta",
                Map.of(ContextualGroundingRail.METADATA_GROUNDING_SOURCES,
                        List.of("unrelated"),
                        ContextualGroundingRail.METADATA_GROUNDING_THRESHOLD, "not-a-number"));

        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.BLOCK);
    }

    @Test
    @DisplayName("output with no alphanumeric tokens passes (no claims to verify)")
    void outputWithNoAlphaTokensPasses() {
        setProvider("bedrock");
        RailContext ctx = RailContext.postLlm("p", "Planner", "?!?!.,",
                Map.of(ContextualGroundingRail.METADATA_GROUNDING_SOURCES, List.of("anything")));

        assertThat(rail.apply(ctx).decision()).isEqualTo(com.assurant.brain.enums.RailDecision.PASS);
    }

    @Test
    @DisplayName("blocks when output contains many unsupported claims")
    void hallucination() {
        setProvider("bedrock");
        String response = "Microsoft acquired Anthropic last quarter for trillions of dollars.";
        RailContext ctx = RailContext.postLlm("p", "Planner", response, Map.of(
                ContextualGroundingRail.METADATA_GROUNDING_SOURCES,
                List.of("OrderService class handles checkout."),
                ContextualGroundingRail.METADATA_GROUNDING_THRESHOLD, 0.7));
        RailResult result = rail.apply(ctx);
        assertThat(result.decision()).isEqualTo(com.assurant.brain.enums.RailDecision.BLOCK);
        assertThat(result.rail()).isEqualTo(RailType.GROUNDING);
    }
}
