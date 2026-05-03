package com.assurant.brain.jira;

import com.assurant.brain.dto.request.ProposedTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TicketProposalService")
class TicketProposalServiceTest {

    private ChatModel chatModel;
    private TicketProposalService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        var tracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var ticketProposalRepo = mock(com.assurant.brain.dao.TicketProposalRepository.class);
        service = new TicketProposalService(chatModel, new ObjectMapper(), tracker, props, ticketProposalRepo,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("parses valid JSON array of proposed tickets")
    void parsesValidTickets() {
        mockChatResponse("""
                [
                  {"title":"Add retry logic","description":"Implement retry","acceptanceCriteria":"- Max 3 retries","issueType":"Story","storyPoints":3,"priority":"High"},
                  {"title":"Add retry tests","description":"Test coverage","acceptanceCriteria":"- Unit tests","issueType":"Task","storyPoints":2,"priority":"Medium"}
                ]
                """);

        List<ProposedTicket> result = service.propose("Add payment retry logic");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Add retry logic");
        assertThat(result.get(0).storyPoints()).isEqualTo(3);
        assertThat(result.get(1).issueType()).isEqualTo("Task");
    }

    @Test
    @DisplayName("strips markdown fences from LLM response")
    void stripsMarkdownFences() {
        mockChatResponse("""
                ```json
                [{"title":"Test","description":"desc","acceptanceCriteria":"ac","issueType":"Story","storyPoints":1,"priority":"Low"}]
                ```
                """);

        List<ProposedTicket> result = service.propose("Test requirement");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Test");
    }

    @Test
    @DisplayName("throws on malformed JSON")
    void throwsOnMalformed() {
        mockChatResponse("This is not JSON at all");

        assertThatThrownBy(() -> service.propose("Bad input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("handles empty array from LLM")
    void handlesEmptyArray() {
        mockChatResponse("[]");

        List<ProposedTicket> result = service.propose("Trivial requirement");
        assertThat(result).isEmpty();
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
