package com.assurant.brain.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Log4j2
@Service
@RequiredArgsConstructor
public class IterativeContextEnricher {

    private static final int MAX_ITERATIONS = 2;
    private static final int MAX_QUERY_CHARS = 1500;
    private static final int MAX_REENRICH_TOPK = 8;

    private final HybridRetrieverService hybridRetrieverService;
    private final ObjectMapper objectMapper;

    public List<Document> enrich(String projectId, String requirement, String draftPlan) {
        if (projectId == null || draftPlan == null || draftPlan.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<Document> accumulated = new ArrayList<>();
        String currentQuery = buildQuery(requirement, draftPlan);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (currentQuery == null || currentQuery.isBlank()) break;
            try {
                var docs = hybridRetrieverService.hybridSearch(projectId, currentQuery, MAX_REENRICH_TOPK);
                int added = 0;
                for (var scored : docs) {
                    String key = key(scored.document());
                    if (seen.add(key)) {
                        accumulated.add(scored.document());
                        added++;
                    }
                }
                log.debug("IterativeContextEnricher iter={} query-chars={} added={}",
                        i, currentQuery.length(), added);
                if (added == 0) break;
            } catch (RuntimeException e) {
                log.debug("IterativeContextEnricher iter={} failed: {}", i, e.getMessage());
                break;
            }
            currentQuery = null;
        }
        return accumulated;
    }

    private String buildQuery(String requirement, String draftPlan) {
        StringBuilder sb = new StringBuilder();
        if (requirement != null) sb.append(requirement).append('\n');
        sb.append(extractFocusFromPlan(draftPlan));
        String result = sb.toString().trim();
        return result.length() <= MAX_QUERY_CHARS ? result : result.substring(0, MAX_QUERY_CHARS);
    }

    private String extractFocusFromPlan(String draftPlan) {
        try {
            JsonNode tree = objectMapper.readTree(draftPlan);
            StringBuilder sb = new StringBuilder();
            JsonNode files = tree.path("affectedFiles");
            if (files.isArray()) {
                for (JsonNode f : files) sb.append(f.asText()).append(' ');
            }
            JsonNode steps = tree.path("steps");
            if (steps.isArray()) {
                for (JsonNode s : steps) {
                    JsonNode desc = s.isTextual() ? s : s.path("description");
                    if (desc.isTextual()) sb.append(desc.asText()).append(' ');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return draftPlan.length() > 1000 ? draftPlan.substring(0, 1000) : draftPlan;
        }
    }

    private String key(Document doc) {
        Object chunkName = doc.getMetadata().get("chunkName");
        Object filePath = doc.getMetadata().get("filePath");
        return (chunkName != null ? chunkName.toString() : "")
                + "|" + (filePath != null ? filePath.toString() : "");
    }
}
