package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ImitationCorpusBuilder")
class ImitationCorpusBuilderTest {

    @Test
    @DisplayName("blank projectId → no examples")
    void blankProjectIdEmpty() {
        VectorStore vs = mock(VectorStore.class);
        ImitationCorpusBuilder b = new ImitationCorpusBuilder(vs);
        assertThat(b.findExamples("", ImitationCorpusBuilder.TaskType.GENERIC)).isEmpty();
    }

    @Test
    @DisplayName("vector store throws → empty list, never propagates")
    void vectorStoreFailureSafe() {
        VectorStore vs = mock(VectorStore.class);
        when(vs.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("db down"));
        ImitationCorpusBuilder b = new ImitationCorpusBuilder(vs);
        assertThat(b.findExamples("proj", ImitationCorpusBuilder.TaskType.ADD_ENDPOINT)).isEmpty();
    }

    @Test
    @DisplayName("returns up to 3 examples with file paths and bounded content budget")
    void capsExamplesAndBudget() {
        VectorStore vs = mock(VectorStore.class);
        Document d1 = new Document("public class A {}", Map.of("filePath", "A.java"));
        Document d2 = new Document("public class B {}", Map.of("filePath", "B.java"));
        Document d3 = new Document("public class C {}", Map.of("filePath", "C.java"));
        Document d4 = new Document("public class D {}", Map.of("filePath", "D.java"));
        when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(d1, d2, d3, d4));

        ImitationCorpusBuilder b = new ImitationCorpusBuilder(vs);
        var examples = b.findExamples("proj", ImitationCorpusBuilder.TaskType.GENERIC);
        assertThat(examples).hasSize(3);
        assertThat(examples).extracting(ImitationCorpusBuilder.Example::filePath)
                .containsExactly("A.java", "B.java", "C.java");
    }

    @Test
    @DisplayName("renderForPrompt produces a non-empty prompt section with file headers")
    void renderForPromptNonEmpty() {
        VectorStore vs = mock(VectorStore.class);
        ImitationCorpusBuilder b = new ImitationCorpusBuilder(vs);
        String rendered = b.renderForPrompt(List.of(
                new ImitationCorpusBuilder.Example("X.java", "public class X {}")));
        assertThat(rendered).contains("HOUSE STYLE EXAMPLES").contains("X.java").contains("public class X {}");
    }

    @Test
    @DisplayName("renderForPrompt with empty list returns empty string")
    void renderForPromptEmpty() {
        VectorStore vs = mock(VectorStore.class);
        ImitationCorpusBuilder b = new ImitationCorpusBuilder(vs);
        assertThat(b.renderForPrompt(List.of())).isEmpty();
    }
}
