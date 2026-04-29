package com.assurant.brain.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CrossEncoderReRanker")
class CrossEncoderReRankerTest {

    private final CrossEncoderReRanker reranker = new CrossEncoderReRanker(
            org.mockito.Mockito.mock(com.assurant.brain.monitor.TokenUsageTracker.class));

    @Test
    @DisplayName("returns empty list when query is blank")
    void emptyOnBlank() {
        assertThat(reranker.rerank("", List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("docs with stronger token overlap rerank above docs with weaker overlap")
    void textMatchWins() {
        var strong = new HybridRetrieverService.ScoredDocument(
                new Document("class OrderService handles order checkout payment",
                        Map.of("chunkName", "OrderService")), 0.5);
        var weak = new HybridRetrieverService.ScoredDocument(
                new Document("class CalculatorWidget unrelated math",
                        Map.of("chunkName", "Calculator")), 0.5);

        var ranked = reranker.rerank("checkout order payment", List.of(weak, strong), 2);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).document().getMetadata().get("chunkName")).isEqualTo("OrderService");
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    @DisplayName("respects topK limit")
    void respectsTopK() {
        List<HybridRetrieverService.ScoredDocument> candidates = List.of(
                new HybridRetrieverService.ScoredDocument(new Document("a service", Map.of("chunkName", "A")), 0.5),
                new HybridRetrieverService.ScoredDocument(new Document("b service", Map.of("chunkName", "B")), 0.4),
                new HybridRetrieverService.ScoredDocument(new Document("c service", Map.of("chunkName", "C")), 0.3));

        var ranked = reranker.rerank("service", candidates, 2);

        assertThat(ranked).hasSize(2);
    }
}
