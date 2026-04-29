package com.assurant.brain.retrieval;

import com.assurant.brain.dao.ChunkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class HybridRetrieverService {

    private static final int RRF_K = 60;
    private static final int DEFAULT_TOP_K = 10;
    private static final int CANDIDATE_MULTIPLIER = 4;

    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;

    public record ScoredDocument(Document document, double rrfScore) {}

    public List<ScoredDocument> hybridSearch(String projectId, String query) {
        return hybridSearch(projectId, query, DEFAULT_TOP_K);
    }

    public List<ScoredDocument> hybridSearch(String projectId, String query, int topK) {
        if (projectId == null || query == null || query.isBlank()) return List.of();
        int candidateK = Math.max(topK * CANDIDATE_MULTIPLIER, 20);

        List<Document> dense = denseSearch(projectId, query, candidateK);
        List<Document> lexical = lexicalSearch(projectId, query, candidateK);

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();

        for (int i = 0; i < dense.size(); i++) {
            Document doc = dense.get(i);
            String key = key(doc);
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            byKey.putIfAbsent(key, doc);
        }
        for (int i = 0; i < lexical.size(); i++) {
            Document doc = lexical.get(i);
            String key = key(doc);
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            byKey.putIfAbsent(key, doc);
        }

        List<ScoredDocument> ranked = new ArrayList<>();
        scores.forEach((k, v) -> ranked.add(new ScoredDocument(byKey.get(k), v)));
        ranked.sort((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()));
        return ranked.size() <= topK ? ranked : ranked.subList(0, topK);
    }

    private List<Document> denseSearch(String projectId, String query, int topK) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(b.eq("projectId", projectId).build())
                    .build());
        } catch (RuntimeException e) {
            log.debug("Dense search failed for project={}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    private static final int MIN_LEXICAL_QUERY_CHARS = 3;
    private static final int MAX_LEXICAL_QUERY_CHARS = 256;

    private List<Document> lexicalSearch(String projectId, String query, int topK) {
        if (query == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.length() < MIN_LEXICAL_QUERY_CHARS) return List.of();
        if (trimmed.length() > MAX_LEXICAL_QUERY_CHARS) trimmed = trimmed.substring(0, MAX_LEXICAL_QUERY_CHARS);
        try {
            List<Object[]> rows = chunkRepository.lexicalSearch(projectId, trimmed, topK);
            List<Document> results = new ArrayList<>();
            for (Object[] row : rows) {
                String content = row[0] == null ? "" : row[0].toString();
                Map<String, Object> metadata = parseMetadata(row[1]);
                results.add(new Document(content, metadata));
            }
            return results;
        } catch (RuntimeException e) {
            log.debug("Lexical search failed for project={}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parseMetadata(Object metadataField) {
        if (metadataField == null) return Map.of();
        try {
            String json = metadataField.toString();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse chunk metadata: {}", e.getMessage());
            return Map.of();
        }
    }

    private String key(Document doc) {
        Object chunk = doc.getMetadata().get("chunkName");
        Object path = doc.getMetadata().get("filePath");
        return (chunk != null ? chunk.toString() : "") + "|" + (path != null ? path.toString() : "")
                + "|" + Integer.toHexString(doc.getText() == null ? 0 : doc.getText().hashCode());
    }
}
