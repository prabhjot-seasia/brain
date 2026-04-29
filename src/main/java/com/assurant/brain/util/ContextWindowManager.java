package com.assurant.brain.util;

import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Log4j2
public final class ContextWindowManager {

    private ContextWindowManager() {}

    public static String buildContext(List<Document> codeDocs, List<Document> docDocs, int tokenBudget) {
        return buildContext(codeDocs, docDocs, tokenBudget, 1.5);
    }

    public static String buildContext(List<Document> codeDocs, List<Document> docDocs, int tokenBudget, double docTrustMultiplier) {
        List<ScoredSegment> segments = new ArrayList<>();

        for (Document doc : docDocs) {
            String formatted = formatDocument(doc, "DOC");
            double score = extractScore(doc) * docTrustMultiplier;
            segments.add(new ScoredSegment(formatted, score, TokenEstimator.estimate(formatted)));
        }

        for (Document doc : codeDocs) {
            String formatted = formatDocument(doc, "CODE");
            double score = extractScore(doc);
            segments.add(new ScoredSegment(formatted, score, TokenEstimator.estimate(formatted)));
        }

        segments.sort(Comparator.comparingDouble(ScoredSegment::score).reversed());

        StringBuilder context = new StringBuilder();
        int tokensUsed = 0;
        int included = 0;
        int dropped = 0;

        for (ScoredSegment segment : segments) {
            if (tokensUsed + segment.tokens() > tokenBudget) {
                dropped++;
                continue;
            }
            context.append(segment.text()).append("\n---\n");
            tokensUsed += segment.tokens();
            included++;
        }

        if (dropped > 0) {
            log.debug("ContextWindowManager: included {} segments ({} tokens), dropped {} to stay within budget of {}",
                    included, tokensUsed, dropped, tokenBudget);
        }

        return context.toString();
    }

    public static String pruneConventions(List<String> conventions, int maxCount) {
        if (conventions.size() <= maxCount) {
            return String.join("\n", conventions);
        }
        log.debug("Pruning conventions from {} to top-{}", conventions.size(), maxCount);
        return String.join("\n", conventions.subList(0, maxCount));
    }

    public static String pruneGraphContext(List<Object> affectedClasses, int maxCount) {
        if (affectedClasses.isEmpty()) return "No specific classes matched in graph.";
        List<Object> pruned = affectedClasses.size() <= maxCount
                ? affectedClasses
                : affectedClasses.subList(0, maxCount);
        StringBuilder sb = new StringBuilder("Potentially affected classes:\n");
        pruned.forEach(c -> sb.append(c.toString()).append("\n"));
        return sb.toString();
    }

    private static String formatDocument(Document doc, String prefix) {
        Map<String, Object> metadata = doc.getMetadata();
        String chunkName = metadata != null ? String.valueOf(metadata.getOrDefault("chunkName", "unknown")) : "unknown";
        return "[" + prefix + "] " + chunkName + ":\n" + doc.getText();
    }

    private static double extractScore(Document doc) {
        if (doc.getMetadata() != null && doc.getMetadata().containsKey("score")) {
            Object score = doc.getMetadata().get("score");
            if (score instanceof Number) return ((Number) score).doubleValue();
        }
        return 0.5;
    }

    private record ScoredSegment(String text, double score, int tokens) {}
}
