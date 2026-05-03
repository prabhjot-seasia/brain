package com.assurant.brain.incident;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IncidentExplainerService")
class IncidentExplainerServiceTest {

    private ChatModel chatModel;
    private IncidentNodeRepository repository;
    private TokenUsageTracker tracker;
    private RailChain railChain;
    private IncidentExplainerService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        repository = mock(IncidentNodeRepository.class);
        tracker = mock(TokenUsageTracker.class);
        railChain = mock(RailChain.class);
        when(railChain.applyPreLlm(any(RailContext.class))).thenAnswer(inv -> {
            RailContext ctx = inv.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);        service = new IncidentExplainerService(chatModel, repository, tracker, railChain, props);
    }

    @Test
    @DisplayName("explainIncident throws when projectId or classHint is blank")
    void rejectsBlankInputs() {
        assertThatThrownBy(() -> service.explainIncident("", "OrderService"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projectId");
        assertThatThrownBy(() -> service.explainIncident("p", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("classHint");
    }

    @Test
    @DisplayName("returns 'no incidents' summary when repository finds none")
    void noIncidentsReturnsStubSummary() {
        when(repository.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(List.of());

        String md = service.explainIncident("p", "OrderService");

        assertThat(md).contains("No incidents recorded").contains("OrderService");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("calls LLM with sanitized prompt + tracks tokens when incidents present")
    void invokesLlmAndTracker() {
        IncidentNode i = new IncidentNode();
        i.setTitle("OrderService NPE");
        i.setSeverity("SEV2");
        i.setStatus("OPEN");
        i.setRootCauseSummary("Null map default");
        i.setAffectedClassHints(List.of("OrderService"));
        when(repository.findByProjectIdAndClassHintMatch(anyString(), anyString()))
                .thenReturn(List.of(i));
        mockChatResponse("### Summary\nIncident X.");

        String md = service.explainIncident("p", "OrderService");

        assertThat(md).contains("### Summary");
        verify(railChain).applyPreLlm(any(RailContext.class));
        verify(tracker).track(anyString(), any(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private void mockChatResponse(String text) {
        AssistantMessage msg = mock(AssistantMessage.class);
        when(msg.getText()).thenReturn(text);
        Generation gen = mock(Generation.class);
        when(gen.getOutput()).thenReturn(msg);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(gen);
        when(chatModel.call(any(Prompt.class))).thenReturn(resp);
    }
}
