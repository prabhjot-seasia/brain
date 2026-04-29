package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ProjectAffinityDetector")
class ProjectAffinityDetectorTest {

    private ChatModel chatModel;
    private ProjectNodeRepository projectNodeRepository;
    private TokenUsageTracker tokenUsageTracker;
    private RailChain railChain;
    private ProjectAffinityDetector detector;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        projectNodeRepository = mock(ProjectNodeRepository.class);
        tokenUsageTracker = mock(TokenUsageTracker.class);
        railChain = mock(RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(i -> {
            RailContext ctx = i.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        when(railChain.applyPostLlm(any())).thenAnswer(i -> {
            RailContext ctx = i.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        detector = new ProjectAffinityDetector(chatModel, new ObjectMapper(), projectNodeRepository,
                tokenUsageTracker, props, railChain);
    }

    @Test
    @DisplayName("returns empty when no projects are indexed")
    void emptyRegistryReturnsEmpty() {
        when(projectNodeRepository.findAll()).thenReturn(List.of());

        List<AffectedProject> result = detector.detect("Add a logging filter");

        assertThat(result).isEmpty();
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("parses valid LLM response and tracks tokens + guardrails")
    void parsesValidResponse() {
        when(projectNodeRepository.findAll()).thenReturn(List.of(
                projectNode("payments-api", "java", "spring-boot"),
                projectNode("frontend", "typescript", "react")
        ));
        mockChatResponse("""
                {
                  "affectedProjects": [
                    {"projectId": "payments-api", "confidence": 0.9, "rationale": "Requirement names payment flow"},
                    {"projectId": "frontend", "confidence": 0.6, "rationale": "UI needs the new status code"}
                  ],
                  "reasoning": "Payment API owns the backend change; frontend consumes the new response"
                }
                """);

        List<AffectedProject> result = detector.detect("Return 423 for blocked payments");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).projectId()).isEqualTo("payments-api");
        assertThat(result.get(0).confidence()).isEqualTo(0.9);
        verify(tokenUsageTracker).track(eq("ProjectAffinityDetector"),
                eq(LlmOperation.MULTI_REPO_DETECT), isNull(),
                anyString(), anyString(), anyLong(), eq(false), anyString());
        verify(railChain).applyPreLlm(any());
        verify(railChain).applyPostLlm(any());
    }

    @Test
    @DisplayName("drops unknown project IDs that the LLM hallucinates")
    void dropsUnknownProjects() {
        when(projectNodeRepository.findAll()).thenReturn(List.of(
                projectNode("payments-api", "java", "spring-boot")
        ));
        mockChatResponse("""
                {
                  "affectedProjects": [
                    {"projectId": "payments-api", "confidence": 0.9, "rationale": "real"},
                    {"projectId": "nonexistent-service", "confidence": 0.7, "rationale": "made up"}
                  ],
                  "reasoning": "..."
                }
                """);

        List<AffectedProject> result = detector.detect("Some requirement");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).projectId()).isEqualTo("payments-api");
    }

    @Test
    @DisplayName("returns empty on malformed LLM response")
    void malformedResponseReturnsEmpty() {
        when(projectNodeRepository.findAll()).thenReturn(List.of(projectNode("x", "java", "spring")));
        mockChatResponse("this is not json");

        List<AffectedProject> result = detector.detect("Requirement");

        assertThat(result).isEmpty();
    }

    private ProjectNode projectNode(String id, String language, String framework) {
        ProjectNode node = new ProjectNode();
        node.setId(id);
        node.setLanguage(language);
        node.setFramework(framework);
        return node;
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
