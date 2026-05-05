package com.assurant.brain.jira;

import com.assurant.brain.dto.request.ProposedTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TicketCreationService")
class TicketCreationServiceTest {

    private JiraClient jiraClient;
    private TicketCreationService service;

    @BeforeEach
    void setup() {
        jiraClient = mock(JiraClient.class);
        service = new TicketCreationService(jiraClient,
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("creates tickets and returns Jira keys")
    void createsTicketsSuccessfully() {
        when(jiraClient.createIssue(any()))
                .thenReturn(Map.of("key", "PROJ-1"))
                .thenReturn(Map.of("key", "PROJ-2"));

        List<ProposedTicket> tickets = List.of(
                new ProposedTicket("Add retry", "desc", "criteria", "Story", 3, "High"),
                new ProposedTicket("Add tests", "desc2", "criteria2", "Task", 2, "Medium")
        );

        List<String> result = service.createTickets("PROJ", tickets);

        assertThat(result).containsExactly("PROJ-1", "PROJ-2");
    }

    @Test
    @DisplayName("records failure prefix when Jira API throws")
    void handlesJiraFailure() {
        when(jiraClient.createIssue(any()))
                .thenThrow(new RuntimeException("Jira unavailable"));

        List<ProposedTicket> tickets = List.of(
                new ProposedTicket("Failing ticket", "desc", "ac", "Story", 1, "Low")
        );

        List<String> result = service.createTickets("PROJ", tickets);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).startsWith(TicketCreationService.FAILED_PREFIX);
        assertThat(result.get(0)).contains("Failing ticket");
    }

    @Test
    @DisplayName("handles mix of success and failure")
    void mixedResults() {
        when(jiraClient.createIssue(any()))
                .thenReturn(Map.of("key", "PROJ-1"))
                .thenThrow(new RuntimeException("503"));

        List<ProposedTicket> tickets = List.of(
                new ProposedTicket("OK ticket", "d", "c", "Story", 1, "High"),
                new ProposedTicket("Bad ticket", "d", "c", "Task", 1, "Medium")
        );

        List<String> result = service.createTickets("PROJ", tickets);

        assertThat(result.get(0)).isEqualTo("PROJ-1");
        assertThat(result.get(1)).startsWith(TicketCreationService.FAILED_PREFIX);
    }

    @Test
    @DisplayName("returns empty list for empty tickets")
    void emptyTickets() {
        List<String> result = service.createTickets("PROJ", List.of());
        assertThat(result).isEmpty();
    }
}
