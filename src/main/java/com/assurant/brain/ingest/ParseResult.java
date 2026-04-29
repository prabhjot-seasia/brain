package com.assurant.brain.ingest;

import java.util.List;
import java.util.Map;

public record ParseResult(
        List<ContextGap> detectedGaps,
        Map<String, Object> stats) {

    public static ParseResult empty() {
        return new ParseResult(List.of(), Map.of());
    }

    public static ParseResult of(Map<String, Object> stats) {
        return new ParseResult(List.of(), stats);
    }

    public static ParseResult withGaps(List<ContextGap> gaps, Map<String, Object> stats) {
        return new ParseResult(gaps, stats);
    }
}
