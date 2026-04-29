package com.assurant.brain.guardrail;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.enums.RailType;

import java.util.List;

public record RailResult(
        RailType rail,
        RailDecision decision,
        String sanitizedPayload,
        List<String> violations,
        List<String> maskedEntities
) {
    public static RailResult pass(RailType rail) {
        return new RailResult(rail, RailDecision.PASS, null, List.of(), List.of());
    }

    public static RailResult block(RailType rail, String reason) {
        return new RailResult(rail, RailDecision.BLOCK, null, List.of(reason), List.of());
    }

    public static RailResult block(RailType rail, List<String> reasons) {
        return new RailResult(rail, RailDecision.BLOCK, null, reasons, List.of());
    }

    public static RailResult modify(RailType rail, String sanitized, List<String> masked) {
        return new RailResult(rail, RailDecision.MODIFY, sanitized, List.of(), masked);
    }
}
