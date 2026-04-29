package com.assurant.brain.docs;

import com.assurant.brain.cache.SemanticCacheService;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.DocType;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.monitor.TokenUsageTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DocGeneratorService")
class DocGeneratorServiceTest {

    private ChatModel chatModel;
    private VectorStore vectorStore;
    private SemanticCacheService semanticCacheService;
    private TokenUsageTracker tokenUsageTracker;
    private DocGeneratorService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        vectorStore = mock(VectorStore.class);
        var conventionRepo = mock(ConventionNodeRepository.class);
        var projectRepo = mock(ProjectNodeRepository.class);
        semanticCacheService = mock(SemanticCacheService.class);
        tokenUsageTracker = mock(TokenUsageTracker.class);

        var rag = new BrainProperties.Rag(5, 3, 6000, 1.5, 1.0, 10, 5);
        var llm = new BrainProperties.Llm("claude-sonnet", "claude-haiku", "anthropic", 4.0);
        var cache = new BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new BrainProperties(llm, null, rag, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);

        when(conventionRepo.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectRepo.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        var documentRepository = mock(com.assurant.brain.dao.GeneratedDocumentRepository.class);
        var railChain = mock(com.assurant.brain.guardrail.RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(inv ->
                new com.assurant.brain.guardrail.RailChain.ChainResult(inv.getArgument(0), List.of()));
        when(railChain.applyPostLlm(any())).thenAnswer(inv ->
                new com.assurant.brain.guardrail.RailChain.ChainResult(inv.getArgument(0), List.of()));
        service = new DocGeneratorService(chatModel, vectorStore, conventionRepo, projectRepo,
                props, semanticCacheService, tokenUsageTracker, documentRepository, railChain);
    }

    @Test
    @DisplayName("returns cached result on cache hit")
    void returnsCachedResult() {
        when(semanticCacheService.get(anyString(), eq("proj-1"), anyString()))
                .thenReturn(Optional.of("cached doc content"));

        String result = service.generate("proj-1", "Explain auth flow", DocType.EXPLANATION);

        assertThat(result).isEqualTo("cached doc content");
        verify(chatModel, never()).call(any(Prompt.class));
        verify(tokenUsageTracker).trackCacheHit(anyString(), any(), eq("proj-1"), anyString(), anyString());
    }

    @Test
    @DisplayName("calls LLM on cache miss and caches result")
    void callsLlmOnCacheMiss() {
        when(semanticCacheService.get(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        mockChatResponse("## Architecture\nGenerated architecture doc");

        String result = service.generate("proj-1", "Show architecture", DocType.ARCHITECTURE);

        assertThat(result).contains("Architecture");
        verify(chatModel).call(any(Prompt.class));
        verify(semanticCacheService).put(anyString(), eq("proj-1"), anyString(), anyString(), anyInt());
        verify(tokenUsageTracker).track(anyString(), any(), eq("proj-1"), anyString(), anyString(),
                anyLong(), eq(false), anyString());
    }

    @Test
    @DisplayName("generates different doc types without error")
    void generatesAllDocTypes() {
        when(semanticCacheService.get(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        mockChatResponse("```mermaid\nflowchart TD\n  A-->B\n```");

        for (DocType type : DocType.values()) {
            String result = service.generate("proj-1", "Generate " + type.name(), type);
            assertThat(result).isNotBlank();
        }
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
