package com.assurant.brain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextWindowManager")
class ContextWindowManagerTest {

    @Test
    @DisplayName("buildContext includes documents within token budget")
    void buildContextWithinBudget() {
        Document doc = new Document("short content", Map.of("chunkName", "Test.java", "sourceType", "CODE"));
        String result = ContextWindowManager.buildContext(List.of(doc), List.of(), 100);
        assertThat(result).contains("[CODE] Test.java");
        assertThat(result).contains("short content");
    }

    @Test
    @DisplayName("buildContext drops documents exceeding budget")
    void buildContextDropsOverBudget() {
        Document large = new Document("x".repeat(1000), Map.of("chunkName", "Big.java", "sourceType", "CODE"));
        String result = ContextWindowManager.buildContext(List.of(large), List.of(), 10);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("buildContext applies DOC trust multiplier")
    void buildContextDocTrustWeight() {
        Document code = new Document("code", Map.of("chunkName", "A.java", "sourceType", "CODE", "score", 0.5));
        Document doc = new Document("docs", Map.of("chunkName", "README.md", "sourceType", "DOC", "score", 0.5));
        String result = ContextWindowManager.buildContext(List.of(code), List.of(doc), 1000);
        int docIdx = result.indexOf("[DOC]");
        int codeIdx = result.indexOf("[CODE]");
        assertThat(docIdx).isLessThan(codeIdx);
    }

    @Test
    @DisplayName("pruneConventions limits to maxCount")
    void pruneConventions() {
        List<String> conventions = List.of("c1", "c2", "c3", "c4", "c5");
        String result = ContextWindowManager.pruneConventions(conventions, 3);
        assertThat(result).isEqualTo("c1\nc2\nc3");
    }

    @Test
    @DisplayName("pruneConventions returns all when under limit")
    void pruneConventionsUnderLimit() {
        List<String> conventions = List.of("c1", "c2");
        String result = ContextWindowManager.pruneConventions(conventions, 10);
        assertThat(result).isEqualTo("c1\nc2");
    }

    @Test
    @DisplayName("pruneGraphContext limits affected classes")
    void pruneGraphContext() {
        List<Object> classes = List.of("ClassA", "ClassB", "ClassC", "ClassD", "ClassE", "ClassF");
        String result = ContextWindowManager.pruneGraphContext(classes, 3);
        assertThat(result).contains("ClassA").contains("ClassC");
        assertThat(result).doesNotContain("ClassD");
    }

    @Test
    @DisplayName("pruneGraphContext handles empty list")
    void pruneGraphContextEmpty() {
        String result = ContextWindowManager.pruneGraphContext(List.of(), 5);
        assertThat(result).contains("No specific classes matched");
    }
}
