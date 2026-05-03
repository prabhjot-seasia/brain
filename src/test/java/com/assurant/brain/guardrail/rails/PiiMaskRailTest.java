package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.InjectionClassifierType;
import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiMaskRail")
class PiiMaskRailTest {

    private PiiMaskRail build(boolean blockMode) {
        var guardrails = new BrainProperties.Guardrails(20000, 50000, 30000, blockMode, true, InjectionClassifierType.REGEX);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, guardrails, null, null, null, null, null, null, null, null);
        return new PiiMaskRail(props);
    }

    @Test
    @DisplayName("masks SSN in MODIFY mode")
    void masksSSN() {
        RailResult result = build(false).apply(RailContext.preLlm("p", "svc", "My SSN is 123-45-6789 for records"));
        assertThat(result.decision()).isEqualTo(RailDecision.MODIFY);
        assertThat(result.sanitizedPayload()).contains("[PII-SSN]");
        assertThat(result.sanitizedPayload()).doesNotContain("123-45-6789");
        assertThat(result.maskedEntities()).contains("[PII-SSN]");
    }

    @Test
    @DisplayName("masks email and phone")
    void masksMultipleTypes() {
        RailResult result = build(false).apply(
                RailContext.preLlm("p", "svc", "Contact john@example.com or 415-555-1234"));
        assertThat(result.decision()).isEqualTo(RailDecision.MODIFY);
        assertThat(result.sanitizedPayload()).contains("[PII-EMAIL]").contains("[PII-PHONE]");
    }

    @Test
    @DisplayName("masks JWT tokens")
    void masksJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123";
        RailResult result = build(false).apply(RailContext.preLlm("p", "svc", "Token: " + jwt));
        assertThat(result.decision()).isEqualTo(RailDecision.MODIFY);
        assertThat(result.sanitizedPayload()).contains("[PII-JWT]");
    }

    @Test
    @DisplayName("passes when no PII")
    void passesClean() {
        RailResult result = build(false).apply(RailContext.preLlm("p", "svc", "Just a normal requirement"));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("blocks when blockOnPiiDetection enabled")
    void blocksWhenConfigured() {
        RailResult result = build(true).apply(RailContext.preLlm("p", "svc", "SSN: 111-22-3333"));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
    }
}
