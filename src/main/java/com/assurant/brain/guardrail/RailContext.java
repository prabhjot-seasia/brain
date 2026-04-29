package com.assurant.brain.guardrail;

import com.assurant.brain.enums.RailPhase;

import java.util.Map;

public record RailContext(
        RailPhase phase,
        String projectId,
        String sourceService,
        String rawInput,
        String sanitizedInput,
        String rawOutput,
        Object parsedOutput,
        Map<String, Object> metadata
) {
    public static RailContext preLlm(String projectId, String sourceService, String input) {
        return new RailContext(RailPhase.PRE_LLM, projectId, sourceService, input, input, null, null, Map.of());
    }

    public static RailContext preLlm(String projectId, String sourceService, String input, Map<String, Object> metadata) {
        return new RailContext(RailPhase.PRE_LLM, projectId, sourceService, input, input, null, null, metadata);
    }

    public static RailContext postLlm(String projectId, String sourceService, String output, Map<String, Object> metadata) {
        return new RailContext(RailPhase.POST_LLM, projectId, sourceService, null, null, output, null, metadata);
    }

    public RailContext withSanitizedInput(String sanitized) {
        return new RailContext(phase, projectId, sourceService, rawInput, sanitized, rawOutput, parsedOutput, metadata);
    }

    public RailContext withParsedOutput(Object parsed) {
        return new RailContext(phase, projectId, sourceService, rawInput, sanitizedInput, rawOutput, parsed, metadata);
    }
}
