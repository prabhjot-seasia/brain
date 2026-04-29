package com.assurant.brain.jira;

import com.assurant.brain.dao.TicketProposalRepository;
import com.assurant.brain.domain.TicketProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@DisplayName("JiraTicketController")
class JiraTicketControllerTest {

    private TicketProposalService ticketProposalService;
    private TicketCreationService ticketCreationService;
    private TicketProposalRepository ticketProposalRepository;
    private JiraTicketController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        ticketProposalService = mock(TicketProposalService.class);
        ticketCreationService = mock(TicketCreationService.class);
        ticketProposalRepository = mock(TicketProposalRepository.class);
        when(ticketProposalRepository.save(any(TicketProposal.class))).thenAnswer(inv -> {
            TicketProposal tp = inv.getArgument(0);
            tp.setId(UUID.randomUUID());
            return tp;
        });
        var asyncJobs = mock(com.assurant.brain.jobs.AsyncJobService.class);
        var stubJob = new com.assurant.brain.jobs.AsyncJob(
                UUID.randomUUID(), "TICKET_PROPOSAL", "PROPOSAL", "x", null,
                com.assurant.brain.jobs.AsyncJobStatus.QUEUED,
                null, null, null, null, null, null, null,
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), false);
        when(asyncJobs.startOrAttach(any(), any(), any(), any())).thenReturn(stubJob);
        controller = new JiraTicketController(ticketProposalService, ticketCreationService,
                ticketProposalRepository, objectMapper, asyncJobs);
    }

    @Test
    @DisplayName("propose returns 202 and queues async processing")
    void proposeSuccess() {
        ResponseEntity<Map<String, Object>> response = controller.propose(Map.of(
                "content", "Add authentication",
                "projectKey", "PROJ"
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("status", "PROPOSED");
        assertThat(response.getBody()).containsKey("proposalId");
        verify(ticketProposalService).proposeAsync(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("propose returns 400 when content is missing")
    void proposeMissingContent() {
        ResponseEntity<Map<String, Object>> response = controller.propose(Map.of(
                "projectKey", "PROJ"
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("propose returns 400 when projectKey is blank")
    void proposeBlankProjectKey() {
        ResponseEntity<Map<String, Object>> response = controller.propose(Map.of(
                "content", "Something",
                "projectKey", "   "
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("create returns 400 when tickets array is empty")
    void createEmptyTickets() {
        ResponseEntity<Map<String, Object>> response = controller.create(Map.of(
                "tickets", List.of()
        ));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("create delegates to ticketCreationService")
    void createSuccess() {
        UUID proposalId = UUID.randomUUID();
        TicketProposal proposal = new TicketProposal();
        proposal.setId(proposalId);
        proposal.setJiraProjectKey("PROJ");
        when(ticketProposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(ticketProposalRepository.save(any())).thenReturn(proposal);

        List<Map<String, Object>> ticketMaps = List.of(Map.of(
                "title", "Add auth",
                "description", "desc",
                "acceptanceCriteria", "ac",
                "issueType", "Story",
                "storyPoints", 3,
                "priority", "High"
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("proposalId", proposalId.toString());
        body.put("tickets", ticketMaps);

        ResponseEntity<Map<String, Object>> response = controller.create(body);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsKey("jobId");
        assertThat(response.getBody()).containsKey("streamUrl");
        verify(ticketCreationService).createTicketsAsync(eq("anonymous"), eq("PROJ"), anyList(), any(UUID.class));
    }

    @Test
    @DisplayName("listProposals returns list")
    void listProposals() {
        when(ticketProposalRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of());
        ResponseEntity<List<TicketProposal>> response = controller.listProposals("user-1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("getProposalStatus returns 404 when proposal not found")
    void getProposalStatusNotFound() {
        UUID id = UUID.randomUUID();
        when(ticketProposalRepository.findById(id)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> response = controller.getProposalStatus(id);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("getProposalStatus returns 200 with proposal status when found")
    void getProposalStatusFound() {
        UUID id = UUID.randomUUID();
        TicketProposal tp = new TicketProposal();
        tp.setId(id);
        tp.setStatus("READY");
        tp.setJiraProjectKey("PROJ");
        when(ticketProposalRepository.findById(id)).thenReturn(Optional.of(tp));
        ResponseEntity<Map<String, Object>> response = controller.getProposalStatus(id);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("proposalId", id.toString())
                .containsEntry("status", "READY")
                .containsEntry("projectKey", "PROJ");
    }

    @Test
    @DisplayName("getProposalStatus emits tickets + count when proposed tickets exist")
    void getProposalStatusWithTickets() {
        UUID id = UUID.randomUUID();
        TicketProposal tp = new TicketProposal();
        tp.setId(id);
        tp.setStatus("READY");
        tp.setJiraProjectKey("PROJ");
        tp.setProposedTickets(List.of(Map.of("title", "T1"), Map.of("title", "T2")));
        when(ticketProposalRepository.findById(id)).thenReturn(Optional.of(tp));
        ResponseEntity<Map<String, Object>> response = controller.getProposalStatus(id);
        assertThat(response.getBody()).containsEntry("count", 2);
        assertThat(response.getBody()).containsKey("tickets");
    }

    @Test
    @DisplayName("create returns 400 when proposalId is missing")
    void createMissingProposalId() {
        Map<String, Object> body = new HashMap<>();
        body.put("tickets", List.of(Map.of("title", "X")));
        ResponseEntity<Map<String, Object>> response = controller.create(body);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
