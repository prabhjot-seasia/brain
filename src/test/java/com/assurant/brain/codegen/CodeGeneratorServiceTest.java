package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CodeGeneratorService")
class CodeGeneratorServiceTest {

    private ChatModel chatModel;
    private VectorStore vectorStore;
    private ConventionNodeRepository conventionNodeRepository;
    private CodeGeneratorService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        vectorStore = mock(VectorStore.class);
        conventionNodeRepository = mock(ConventionNodeRepository.class);

        var rag = new BrainProperties.Rag(5, 3, 4000, 1.5, 1.0, 10, 5);
        var props = new BrainProperties(null, null, rag, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var adaptivePromptBuilder = mock(AdaptivePromptBuilder.class);
        when(adaptivePromptBuilder.buildAdaptiveSection(anyString())).thenReturn("");
        var aiderFormatter = mock(com.assurant.brain.codegen.AiderDiffFormatter.class);
        var aiderApplier = new com.assurant.brain.codegen.AiderDiffApplier();
        var symbolDictionaryBuilder = mock(SymbolDictionaryBuilder.class);
        when(symbolDictionaryBuilder.build(anyString())).thenReturn(SymbolDictionary.EMPTY);
        var styleFingerprintBuilder = new StyleFingerprintBuilder();
        service = new CodeGeneratorService(chatModel, vectorStore, conventionNodeRepository, props,
                new ObjectMapper(), tracker, adaptivePromptBuilder, aiderFormatter, aiderApplier,
                symbolDictionaryBuilder, styleFingerprintBuilder);
    }

    @Test
    @DisplayName("generateCode retrieves RAG context and returns parsed file map")
    void generateCodeHappyPath() {
        Document codeDoc = new Document("class Foo {}", Map.of("chunkName", "Foo.java", "sourceType", "CODE"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(codeDoc));
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1")).thenReturn(List.of());

        mockChatResponse("{\"src/main/java/Foo.java\": \"package com;\\nclass Foo {}\"}");

        Map<String, String> result = service.generateCode("proj-1", "{\"steps\":[]}", "Add feature");

        assertThat(result).containsKey("src/main/java/Foo.java");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generateCode strips markdown fences from LLM response")
    void generateCodeStripsFences() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1")).thenReturn(List.of());

        mockChatResponse("```json\n{\"src/Foo.java\": \"code\"}\n```");

        Map<String, String> result = service.generateCode("proj-1", "{}", "req");
        assertThat(result).containsKey("src/Foo.java");
    }

    @Test
    @DisplayName("generateCode throws on invalid JSON response")
    void generateCodeInvalidJson() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1")).thenReturn(List.of());

        mockChatResponse("not valid json");

        assertThatThrownBy(() -> service.generateCode("proj-1", "{}", "req"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse LLM-generated code");
    }

    @Test
    @DisplayName("fixCode merges fixes into existing files")
    void fixCodeMergesFiles() {
        mockChatResponse("{\"src/Foo.java\": \"fixed code\"}");

        Map<String, String> current = Map.of("src/Foo.java", "broken", "src/Bar.java", "ok");
        Map<String, String> result = service.fixCode(current, List.of("Bug in Foo"), "{}");

        assertThat(result).containsEntry("src/Foo.java", "fixed code");
        assertThat(result).containsEntry("src/Bar.java", "ok");
    }

    @Test
    @DisplayName("generateCode includes conventions in prompt")
    void generateCodeIncludesConventions() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ConventionNode conv = new ConventionNode();
        conv.setRule("Use @RequiredArgsConstructor");
        conv.setSourceFile("CONTRIBUTING.md");
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1")).thenReturn(List.of(conv));

        mockChatResponse("{\"src/Foo.java\": \"code\"}");

        service.generateCode("proj-1", "{}", "req");

        verify(chatModel).call(argThat((Prompt p) -> {
            String text = p.getInstructions().stream()
                    .filter(m -> m.getMessageType().name().equals("USER"))
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .findFirst().orElse("");
            return text.contains("@RequiredArgsConstructor");
        }));
    }

    @Test
    @DisplayName("generateAiderDiffs invokes formatter, applier, and returns updated file map")
    void generateAiderDiffsHappyPath() {
        var aiderFormatter = (com.assurant.brain.codegen.AiderDiffFormatter)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "aiderDiffFormatter");
        when(aiderFormatter.generateBlocks(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("src/Foo.java\n<<<<<<< SEARCH\nint x = 1;\n=======\nint x = 2;\n>>>>>>> REPLACE");
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());

        Map<String, String> result = service.generateAiderDiffs("proj-1", "src/Foo.java",
                "class Foo { int x = 1; }", "Bump x to 2");

        assertThat(result.get("src/Foo.java")).contains("int x = 2;");
        assertThat(result.get("src/Foo.java")).doesNotContain("int x = 1;");
    }

    @Test
    @DisplayName("generateAiderDiffs handles null existingContent without throwing")
    void generateAiderDiffsNullContent() {
        var aiderFormatter = (com.assurant.brain.codegen.AiderDiffFormatter)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "aiderDiffFormatter");
        when(aiderFormatter.generateBlocks(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("");
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());

        Map<String, String> result = service.generateAiderDiffs("proj-1", "src/Foo.java", null, "create Bar");

        assertThat(result).containsKey("src/Foo.java");
        assertThat(result.get("src/Foo.java")).isEqualTo("");
    }

    @Test
    @DisplayName("generateAiderDiffs returns map even when applier reports errors (logs warning)")
    void generateAiderDiffsLogsButReturnsOnApplyErrors() {
        var aiderFormatter = (com.assurant.brain.codegen.AiderDiffFormatter)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "aiderDiffFormatter");
        when(aiderFormatter.generateBlocks(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("src/Foo.java\n<<<<<<< SEARCH\nNOT_PRESENT\n=======\nreplacement\n>>>>>>> REPLACE");
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());

        Map<String, String> result = service.generateAiderDiffs("proj-1", "src/Foo.java",
                "class Foo { int x = 1; }", "Try it");

        assertThat(result).containsKey("src/Foo.java");
        assertThat(result.get("src/Foo.java")).isEqualTo("class Foo { int x = 1; }");
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage output =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
