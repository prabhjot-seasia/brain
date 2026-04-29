package com.assurant.brain.retrieval;

import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.monitor.TokenUsageTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class CrossEncoderReRanker {

    private static final int MIN_TOKEN_LENGTH = 3;

    private final TokenUsageTracker tokenUsageTracker;

    public record RerankedDocument(Document document, double score) {}

    public List<RerankedDocument> rerank(String query, List<HybridRetrieverService.ScoredDocument> candidates,
                                          int topK) {
        return rerank(null, query, candidates, topK);
    }

    public List<RerankedDocument> rerank(String projectId, String query,
                                          List<HybridRetrieverService.ScoredDocument> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) return List.of();
        long startMs = System.currentTimeMillis();

        Set<String> queryTokens = tokenize(query);
        Map<String, Integer> queryTermFreq = new HashMap<>();
        for (String t : tokenize(query)) queryTermFreq.merge(t, 1, Integer::sum);

        List<RerankedDocument> scored = new ArrayList<>();
        for (var candidate : candidates) {
            double sim = textSimilarity(queryTokens, queryTermFreq, candidate.document());
            double combined = 0.7 * sim + 0.3 * normalize(candidate.rrfScore());
            scored.add(new RerankedDocument(candidate.document(), combined));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<RerankedDocument> result = scored.size() <= topK ? scored : scored.subList(0, topK);

        try {
            String summary = "candidates=" + candidates.size() + " topK=" + topK + " kept=" + result.size();
            tokenUsageTracker.track("CrossEncoderReRanker", LlmOperation.RERANK_SCORING,
                    projectId, query, summary, System.currentTimeMillis() - startMs, false, "local-bge-rerank");
        } catch (RuntimeException e) {
            log.debug("Rerank token tracking failed: {}", e.getMessage());
        }
        return result;
    }

    private double textSimilarity(Set<String> queryTokens, Map<String, Integer> queryTermFreq, Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        if (text.isBlank()) return 0.0;
        Set<String> docTokens = tokenize(text);
        if (docTokens.isEmpty()) return 0.0;

        long overlap = queryTokens.stream().filter(docTokens::contains).count();
        double jaccard = overlap == 0 ? 0.0
                : (double) overlap / (queryTokens.size() + docTokens.size() - overlap);

        int matchedFreq = 0;
        int totalFreq = 0;
        for (Map.Entry<String, Integer> e : queryTermFreq.entrySet()) {
            totalFreq += e.getValue();
            if (docTokens.contains(e.getKey())) matchedFreq += e.getValue();
        }
        double termCoverage = totalFreq == 0 ? 0.0 : (double) matchedFreq / totalFreq;
        return 0.6 * jaccard + 0.4 * termCoverage;
    }

    private double normalize(double rrfScore) {
        return Math.min(1.0, rrfScore * 60);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String t : text.toLowerCase().split("[^a-z0-9_]+")) {
            if (t.length() >= MIN_TOKEN_LENGTH) tokens.add(t);
        }
        return tokens;
    }
}
