package com.assurant.brain.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IterativeContextEnricher")
class IterativeContextEnricherTest {

    private HybridRetrieverService hybrid;
    private IterativeContextEnricher enricher;

    @BeforeEach
    void setup() {
        hybrid = mock(HybridRetrieverService.class);
        enricher = new IterativeContextEnricher(hybrid, new ObjectMapper());
    }

    @Test
    @DisplayName("returns empty list when draft plan is blank")
    void emptyOnBlankDraft() {
        assertThat(enricher.enrich("proj-1", "req", "")).isEmpty();
    }

    @Test
    @DisplayName("re-queries hybrid retriever using affectedFiles + step descriptions")
    void enrichesUsingPlan() {
        Document fresh = new Document("class OrderService {}",
                Map.of("chunkName", "OrderService", "filePath", "Order.java"));
        when(hybrid.hybridSearch(eq("proj-1"), anyString(), anyInt()))
                .thenReturn(List.of(new HybridRetrieverService.ScoredDocument(fresh, 0.9)));

        String draftPlan = """
                {"affectedFiles":["src/Order.java"],"steps":[{"description":"add discount field"}]}
                """;
        List<Document> docs = enricher.enrich("proj-1", "Add discount", draftPlan);

        assertThat(docs).hasSize(1);
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(hybrid, atMost(2)).hybridSearch(eq("proj-1"), q.capture(), anyInt());
        assertThat(q.getValue()).contains("Order.java").contains("discount");
    }

    @Test
    @DisplayName("stops early when no new documents are returned")
    void stopsWhenNoNewDocs() {
        when(hybrid.hybridSearch(eq("proj-1"), anyString(), anyInt()))
                .thenReturn(List.of());

        String draftPlan = "{\"affectedFiles\":[],\"steps\":[]}";
        enricher.enrich("proj-1", "req", draftPlan);

        verify(hybrid, times(1)).hybridSearch(eq("proj-1"), anyString(), anyInt());
    }

    @Test
    @DisplayName("falls back to raw plan text when JSON parse fails")
    void nonJsonPlanStillQueries() {
        when(hybrid.hybridSearch(eq("proj-1"), anyString(), anyInt()))
                .thenReturn(List.of());

        enricher.enrich("proj-1", "Add validation", "this is not JSON, just narrative");

        verify(hybrid, atMost(2)).hybridSearch(eq("proj-1"), anyString(), anyInt());
    }
}
