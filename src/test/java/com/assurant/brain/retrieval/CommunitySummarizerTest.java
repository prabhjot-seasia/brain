package com.assurant.brain.retrieval;

import com.assurant.brain.graph.node.CommunitySummaryNode;
import com.assurant.brain.graph.repository.CommunitySummaryNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CommunitySummarizer")
class CommunitySummarizerTest {

    private ChatModel chatModel;
    private CommunitySummaryNodeRepository repository;
    private CommunitySummarizer summarizer;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        repository = mock(CommunitySummaryNodeRepository.class);
        var railChain = mock(com.assurant.brain.guardrail.RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(i -> {
            var ctx = (com.assurant.brain.guardrail.RailContext) i.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        summarizer = new CommunitySummarizer(chatModel, repository, railChain, tracker, props,
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(repository.save(any(CommunitySummaryNode.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("groups classes into package-level + module-level communities (only ≥2 members)")
    void detectsCommunities() {
        var communities = summarizer.detectCommunities(List.of(
                "com.example.order.OrderService",
                "com.example.order.OrderController",
                "com.example.payment.PaymentService",
                "com.other.NoSibling"));

        assertThat(communities)
                .anyMatch(c -> c.key().equals("com.example.order") && c.memberFqns().size() == 2)
                .anyMatch(c -> c.key().equals("com.example") && c.level() == 1);
        assertThat(communities)
                .noneMatch(c -> c.key().equals("com.example.payment"));
    }

    @Test
    @DisplayName("summarize calls LLM and persists CommunitySummaryNode")
    void summarizeCallsLlmAndSaves() {
        when(repository.findByProjectIdAndCommunityKey(anyString(), anyString())).thenReturn(Optional.empty());
        mockChatResponse("Order package handles checkout, retries, and refunds.");

        var community = new CommunitySummarizer.Community(
                "com.example.order", 2, List.of("com.example.order.OrderService", "com.example.order.OrderController"));

        CommunitySummaryNode saved = summarizer.summarize("proj-1", community);

        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getCommunityKey()).isEqualTo("com.example.order");
        assertThat(saved.getSummary()).contains("Order package");
        verify(repository).save(any(CommunitySummaryNode.class));
    }

    @Test
    @DisplayName("LLM failure returns fallback summary, still persists CommunitySummaryNode")
    void summarizeFallsBackWhenLlmFails() {
        when(repository.findByProjectIdAndCommunityKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM offline"));

        var community = new CommunitySummarizer.Community(
                "com.example.order", 2, List.of("com.example.order.OrderService"));

        CommunitySummaryNode saved = summarizer.summarize("proj-1", community);

        assertThat(saved.getSummary()).contains("LLM summary unavailable");
        verify(repository).save(any(CommunitySummaryNode.class));
    }

    @Test
    @DisplayName("FQN with no dot is silently dropped during community detection")
    void fqnWithoutDotSkipped() {
        var communities = summarizer.detectCommunities(List.of("NoPackage"));
        assertThat(communities).isEmpty();
    }

    @Test
    @DisplayName("cache hit when memberCountHash matches existing — no LLM call")
    void cacheHitSkipsLlm() {
        var community = new CommunitySummarizer.Community(
                "com.example.order", 2, List.of("com.example.order.OrderService"));

        CommunitySummaryNode cached = new CommunitySummaryNode();
        cached.setProjectId("proj-1");
        cached.setCommunityKey("com.example.order");
        cached.setSummary("cached");
        cached.setMemberCountHash(hashMembers(community.memberFqns()));

        when(repository.findByProjectIdAndCommunityKey(eq("proj-1"), eq("com.example.order")))
                .thenReturn(Optional.of(cached));

        CommunitySummaryNode out = summarizer.summarize("proj-1", community);

        assertThat(out.getSummary()).isEqualTo("cached");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    private void mockChatResponse(String text) {
        AssistantMessage msg = mock(AssistantMessage.class);
        when(msg.getText()).thenReturn(text);
        Generation g = mock(Generation.class);
        when(g.getOutput()).thenReturn(msg);
        ChatResponse r = mock(ChatResponse.class);
        when(r.getResult()).thenReturn(g);
        when(chatModel.call(any(Prompt.class))).thenReturn(r);
    }

    private String hashMembers(List<String> members) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (String m : members) md.update(m.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(digest.length, 8); i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}
