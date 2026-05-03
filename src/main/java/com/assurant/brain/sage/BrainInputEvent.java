package com.assurant.brain.sage;

import java.util.List;
import java.util.Map;

public record BrainInputEvent(
        BrainInputEventType eventType,
        String projectId,
        String userId,
        String rawInput,
        List<String> extractedSymbols,
        Map<String, Object> metadata) {

    public static BrainInputEvent of(BrainInputEventType eventType, String projectId, String rawInput) {
        return new BrainInputEvent(eventType, projectId, null, rawInput, List.of(), Map.of());
    }
}
