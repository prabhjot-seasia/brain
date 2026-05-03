package com.assurant.brain.learning;

import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.github.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MergedPrAnalyzerService")
class MergedPrAnalyzerServiceTest {

    private ChatModel chatModel;
    private GitHubClient gitHubClient;
    private PullRequestRecordRepository prRecordRepository;
    private ConventionLearner conventionLearner;
    private MergedPrAnalyzerService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        gitHubClient = mock(GitHubClient.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);
        conventionLearner = mock(ConventionLearner.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new MergedPrAnalyzerService(chatModel, gitHubClient, prRecordRepository,
                conventionLearner, new ObjectMapper(), tracker, props);
    }

    @Test
    @DisplayName("throws when PR record not found")
    void throwsWhenPrNotFound() {
        UUID id = UUID.randomUUID();
        when(prRecordRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyzeAndLearn(id, "project-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PR record not found");
    }

    @Test
    @DisplayName("throws when PR has no PR number")
    void throwsWhenNoPrNumber() {
        UUID id = UUID.randomUUID();
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(id);
        pr.setPrNumber(null);
        when(prRecordRepository.findById(id)).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.analyzeAndLearn(id, "project-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no PR number");
    }

    @Test
    @DisplayName("returns empty when no generated files")
    void emptyWhenNoFiles() {
        UUID id = UUID.randomUUID();
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(id);
        pr.setPrNumber(1);
        pr.setRepoUrl("https://github.com/owner/repo");
        pr.setGeneratedFiles(Map.of());
        when(prRecordRepository.findById(id)).thenReturn(Optional.of(pr));
        when(gitHubClient.getPullRequestDiff("owner", "repo", 1)).thenReturn("diff content");

        List<LearningEvent> result = service.analyzeAndLearn(id, "project-1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("analyzes conventions and delegates to learner")
    void analyzesAndLearns() {
        UUID id = UUID.randomUUID();
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(id);
        pr.setPrNumber(5);
        pr.setRepoUrl("https://github.com/owner/repo");
        pr.setGeneratedFiles(Map.of("src/Foo.java", "public class Foo {}"));
        when(prRecordRepository.findById(id)).thenReturn(Optional.of(pr));
        when(gitHubClient.getPullRequestDiff("owner", "repo", 5)).thenReturn("@@ -1 +1 @@\n-old\n+new");

        mockChatResponse("""
                {"followed":["constructor-injection"],"violated":["naming-convention"],"summary":"Minor naming issue"}
                """);

        LearningEvent event = new LearningEvent();
        event.setConventionRule("naming-convention");
        when(conventionLearner.adjustWeights(eq("project-1"), eq(id), anyList(), anyList()))
                .thenReturn(List.of(event));

        List<LearningEvent> result = service.analyzeAndLearn(id, "project-1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getConventionRule()).isEqualTo("naming-convention");
    }

    @Test
    @DisplayName("handles malformed LLM JSON gracefully")
    void handlesMalformedJson() {
        UUID id = UUID.randomUUID();
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(id);
        pr.setPrNumber(3);
        pr.setRepoUrl("https://github.com/owner/repo");
        pr.setGeneratedFiles(Map.of("src/Bar.java", "public class Bar {}"));
        when(prRecordRepository.findById(id)).thenReturn(Optional.of(pr));
        when(gitHubClient.getPullRequestDiff("owner", "repo", 3)).thenReturn("diff");

        mockChatResponse("This is not JSON");

        when(conventionLearner.adjustWeights(eq("project-1"), eq(id), anyList(), anyList()))
                .thenReturn(List.of());

        List<LearningEvent> result = service.analyzeAndLearn(id, "project-1");
        assertThat(result).isEmpty();
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
