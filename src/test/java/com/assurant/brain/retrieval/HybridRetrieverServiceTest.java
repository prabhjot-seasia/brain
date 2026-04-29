package com.assurant.brain.retrieval;

import com.assurant.brain.dao.ChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HybridRetrieverService")
class HybridRetrieverServiceTest {

    private VectorStore vectorStore;
    private ChunkRepository chunkRepository;
    private HybridRetrieverService service;

    @BeforeEach
    void setup() {
        vectorStore = mock(VectorStore.class);
        chunkRepository = mock(ChunkRepository.class);
        service = new HybridRetrieverService(vectorStore, chunkRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("returns empty list when query is blank")
    void emptyOnBlank() {
        assertThat(service.hybridSearch("proj-1", "")).isEmpty();
        assertThat(service.hybridSearch("proj-1", null)).isEmpty();
    }

    @Test
    @DisplayName("RRF fuses dense + lexical results, top of both wins")
    void rrfFusion() {
        Document dense1 = doc("OrderService", "order.java", "class OrderService {}");
        Document dense2 = doc("PaymentService", "payment.java", "class PaymentService {}");
        Document lexical1 = doc("InventoryService", "inventory.java", "class InventoryService {}");
        Document lexical2 = doc("OrderService", "order.java", "class OrderService {}");

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(dense1, dense2));
        when(chunkRepository.lexicalSearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{lexical1.getText(),
                                "{\"chunkName\":\"InventoryService\",\"filePath\":\"inventory.java\"}", 0.9},
                        new Object[]{lexical2.getText(),
                                "{\"chunkName\":\"OrderService\",\"filePath\":\"order.java\"}", 0.7}));

        var results = service.hybridSearch("proj-1", "find services", 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).document().getMetadata().get("chunkName")).isEqualTo("OrderService");
        assertThat(results.get(0).rrfScore()).isGreaterThan(results.get(1).rrfScore());
    }

    @Test
    @DisplayName("dense-only results when lexical errors out")
    void degradesWhenLexicalFails() {
        Document only = doc("Foo", "f.java", "class Foo {}");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(only));
        when(chunkRepository.lexicalSearch(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("trgm not installed"));

        var results = service.hybridSearch("proj-1", "Foo", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).document().getMetadata().get("chunkName")).isEqualTo("Foo");
    }

    @Test
    @DisplayName("malformed metadata JSON degrades to empty metadata, document still returned")
    void malformedMetadataJsonDegrades() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{"some content", "{not valid json", 0.8});
        when(chunkRepository.lexicalSearch(anyString(), anyString(), anyInt()))
                .thenReturn(rows);

        var results = service.hybridSearch("proj-1", "find this", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).document().getText()).isEqualTo("some content");
    }

    @Test
    @DisplayName("query shorter than 3 chars skips lexical search")
    void shortQuerySkipsLexical() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.hybridSearch("proj-1", "ab", 5);

        org.mockito.Mockito.verify(chunkRepository, org.mockito.Mockito.never())
                .lexicalSearch(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("returns empty when both retrievers fail")
    void bothFail() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("vector down"));
        when(chunkRepository.lexicalSearch(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("trgm down"));

        assertThat(service.hybridSearch("proj-1", "anything", 5)).isEmpty();
    }

    private Document doc(String chunkName, String path, String content) {
        return new Document(content, Map.of("chunkName", chunkName, "filePath", path));
    }
}
