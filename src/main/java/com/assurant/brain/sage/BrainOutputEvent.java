package com.assurant.brain.sage;

import java.util.List;
import java.util.Map;

public record BrainOutputEvent(
        BrainOutputEventType eventType,
        String projectId,
        Map<String, String> generatedFiles,
        List<String> referencedSymbols,
        Map<String, Object> metadata) {

    public static BrainOutputEvent of(BrainOutputEventType eventType, String projectId,
                                       Map<String, String> generatedFiles) {
        return new BrainOutputEvent(eventType, projectId, generatedFiles, List.of(), Map.of());
    }
}
