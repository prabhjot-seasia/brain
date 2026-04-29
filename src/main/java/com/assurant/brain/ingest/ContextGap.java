package com.assurant.brain.ingest;

import java.util.Map;

public record ContextGap(ContextGapType type, String message, Map<String, Object> data) {

    public static ContextGap of(ContextGapType type, String message) {
        return new ContextGap(type, message, Map.of());
    }
}
