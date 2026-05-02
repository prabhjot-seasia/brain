package com.assurant.brain.avenger;

import com.assurant.brain.codegen.StyleFingerprint;
import com.assurant.brain.codegen.StyleFingerprintBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class MirageReviewer {

    public enum Verdict { APPROVED, FEELS_LIKE_LLM, REWRITE_TO_MATCH_HOUSE_STYLE }

    public record Report(Verdict verdict, List<String> signals, double maxSigmaOff) {}

    private static final double METHOD_LENGTH_SIGMA_FLAG = 2.0;
    private static final double METHOD_LENGTH_SIGMA_BLOCK = 3.0;
    private static final int FLAG_COUNT_BLOCK_THRESHOLD = 3;

    private final StyleFingerprintBuilder styleFingerprintBuilder;

    public Report review(Map<String, String> generatedFiles, Collection<String> projectSourceSamples) {
        if (generatedFiles == null || generatedFiles.isEmpty()) {
            return new Report(Verdict.APPROVED, List.of(), 0d);
        }
        StyleFingerprint baseline = styleFingerprintBuilder.build(projectSourceSamples);
        if (baseline.isEmpty()) {
            log.debug("MIRAGE: empty baseline (project not yet ingested?), returning APPROVED");
            return new Report(Verdict.APPROVED, List.of(), 0d);
        }

        List<String> javaSources = new ArrayList<>();
        for (String content : generatedFiles.values()) {
            if (content != null && !content.isBlank()) javaSources.add(content);
        }
        StyleFingerprint candidate = styleFingerprintBuilder.build(javaSources);
        if (candidate.isEmpty()) {
            return new Report(Verdict.APPROVED, List.of(), 0d);
        }

        List<String> flagged = new ArrayList<>();
        double maxSigma = 0d;

        double sigma = baseline.methodLinesStdev() > 0
                ? Math.abs(candidate.avgMethodLines() - baseline.avgMethodLines()) / baseline.methodLinesStdev()
                : 0d;
        boolean absoluteOff = baseline.methodLinesStdev() < 1.5
                && candidate.avgMethodLines() > 2 * baseline.avgMethodLines() + 5;
        if (sigma >= METHOD_LENGTH_SIGMA_FLAG || absoluteOff) {
            flagged.add(String.format(
                    "method length: candidate avg %.1f vs baseline %.1f ± %.1f (%.1fσ off)",
                    candidate.avgMethodLines(), baseline.avgMethodLines(),
                    baseline.methodLinesStdev(), sigma));
            maxSigma = Math.max(maxSigma, Math.max(sigma, absoluteOff ? METHOD_LENGTH_SIGMA_BLOCK : 0));
        }

        if (deltaTooFar(candidate.returnEarlyRatio(), baseline.returnEarlyRatio(), 0.3)) {
            flagged.add(String.format(
                    "return-early ratio: candidate %.0f%% vs baseline %.0f%%",
                    candidate.returnEarlyRatio() * 100, baseline.returnEarlyRatio() * 100));
        }
        if (baseline.varUsageRatio() >= 0.3 && candidate.varUsageRatio() <= 0.05) {
            flagged.add(String.format(
                    "var usage: project uses var %.0f%% but candidate %.0f%% — match house style",
                    baseline.varUsageRatio() * 100, candidate.varUsageRatio() * 100));
        }
        if (deltaTooFar(candidate.streamUsageRatio(), baseline.streamUsageRatio(), 0.3)) {
            flagged.add(String.format(
                    "stream usage: candidate %.0f%% vs baseline %.0f%%",
                    candidate.streamUsageRatio() * 100, baseline.streamUsageRatio() * 100));
        }
        if (deltaTooFar(candidate.lambdaUsageRatio(), baseline.lambdaUsageRatio(), 0.3)) {
            flagged.add(String.format(
                    "lambda usage: candidate %.0f%% vs baseline %.0f%%",
                    candidate.lambdaUsageRatio() * 100, baseline.lambdaUsageRatio() * 100));
        }
        if (candidate.commentDensityPct() > 0.1) {
            flagged.add(String.format(
                    "comment density: candidate %.1f%% — codebase rule is 0 comments",
                    candidate.commentDensityPct()));
        }

        Verdict verdict;
        if (flagged.isEmpty()) {
            verdict = Verdict.APPROVED;
        } else if (sigma >= METHOD_LENGTH_SIGMA_BLOCK || flagged.size() >= FLAG_COUNT_BLOCK_THRESHOLD) {
            verdict = Verdict.REWRITE_TO_MATCH_HOUSE_STYLE;
        } else {
            verdict = Verdict.FEELS_LIKE_LLM;
        }
        return new Report(verdict, flagged, maxSigma);
    }

    private boolean deltaTooFar(double candidateRatio, double baselineRatio, double threshold) {
        return Math.abs(candidateRatio - baselineRatio) > threshold;
    }
}
