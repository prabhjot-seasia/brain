package com.assurant.brain.codegen;

public record StyleFingerprint(
        int sampledMethods,
        double avgMethodLines,
        double methodLinesStdev,
        int p90MethodLines,
        double returnEarlyRatio,
        double varUsageRatio,
        double streamUsageRatio,
        double lambdaUsageRatio,
        int avgImportsPerFile,
        double commentDensityPct) {

    public static final StyleFingerprint EMPTY =
            new StyleFingerprint(0, 0d, 0d, 0, 0d, 0d, 0d, 0d, 0, 0d);

    public boolean isEmpty() {
        return sampledMethods == 0;
    }

    public String renderForPrompt() {
        if (isEmpty()) {
            return "(no style fingerprint available — fall back to project conventions)";
        }
        return String.format("""
                HOUSE STYLE — match these characteristics of THIS codebase:
                  avg method length: %.1f lines (std %.1f, p90 %d) — keep new methods proportional
                  return-early ratio: %.0f%% — prefer guard-clauses over deep nesting
                  var usage: %.0f%% — %s
                  stream usage: %.0f%% — %s
                  lambda usage: %.0f%% — %s
                  avg imports per file: %d
                  comment density: %.1f%% — keep code self-documenting
                """,
                avgMethodLines, methodLinesStdev, p90MethodLines,
                returnEarlyRatio * 100,
                varUsageRatio * 100, varUsageRatio >= 0.3 ? "var is acceptable" : "prefer explicit types",
                streamUsageRatio * 100, streamUsageRatio >= 0.3 ? "streams are common" : "loops preferred",
                lambdaUsageRatio * 100, lambdaUsageRatio >= 0.3 ? "lambdas are common" : "lambdas sparingly",
                avgImportsPerFile,
                commentDensityPct);
    }
}
