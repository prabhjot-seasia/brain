package com.assurant.brain.jira;

import com.assurant.brain.dao.TicketProposalRepository;
import com.assurant.brain.domain.TicketProposal;
import com.assurant.brain.dto.request.ProposedTicket;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/api/v1/jira/tickets")
@RequiredArgsConstructor
public class JiraTicketController {

    private final TicketProposalService ticketProposalService;
    private final TicketCreationService ticketCreationService;
    private final TicketProposalRepository ticketProposalRepository;
    private final ObjectMapper objectMapper;
    private final AsyncJobService asyncJobService;

    @PostMapping("/propose")
    public ResponseEntity<Map<String, Object>> propose(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String projectKey = body.get("projectKey");
        String userId = com.assurant.brain.security.SecurityUtils.currentUserId();

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        if (projectKey == null || projectKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectKey is required"));
        }

        TicketProposal record = new TicketProposal();
        record.setSourceText(content);
        record.setJiraProjectKey(projectKey);
        record.setUserId(userId);
        ticketProposalRepository.save(record);

        AsyncJob job = asyncJobService.startOrAttach(
                "TICKET_PROPOSAL", "PROPOSAL", record.getId().toString(), null);
        if (!job.attachedToExisting()) {
            ticketProposalService.proposeAsync(record.getId(), job.id());
        }

        log.info("Ticket proposal queued: id={} project={} job={}", record.getId(), projectKey, job.id());

        return ResponseEntity.accepted().body(Map.of(
                "proposalId", record.getId().toString(),
                "projectKey", projectKey,
                "status", com.assurant.brain.enums.TicketProposalStatus.PROPOSED.name(),
                "jobId", job.id().toString(),
                "streamUrl", "/api/v1/jobs/stream/" + job.id()
        ));
    }

    @GetMapping("/{proposalId}/status")
    public ResponseEntity<Map<String, Object>> getProposalStatus(@PathVariable java.util.UUID proposalId) {
        return ticketProposalRepository.findById(proposalId)
                .map(p -> {
                    Map<String, Object> result = new java.util.HashMap<>(Map.of(
                            "proposalId", p.getId().toString(),
                            "status", p.getStatus(),
                            "projectKey", p.getJiraProjectKey()
                    ));
                    if (p.getProposedTickets() != null && !p.getProposedTickets().isEmpty()) {
                        result.put("tickets", p.getProposedTickets());
                        result.put("count", p.getProposedTickets().size());
                    }
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String proposalId = (String) body.get("proposalId");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ticketMaps = (List<Map<String, Object>>) body.get("tickets");

        if (ticketMaps == null || ticketMaps.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tickets array is required"));
        }

        List<ProposedTicket> tickets = ticketMaps.stream()
                .map(m -> objectMapper.convertValue(m, ProposedTicket.class))
                .toList();

        TicketProposal proposal = proposalId != null
                ? ticketProposalRepository.findById(java.util.UUID.fromString(proposalId)).orElse(null)
                : null;

        String projectKey = proposal != null
                ? proposal.getJiraProjectKey()
                : (String) body.get("projectKey");

        if (projectKey == null || projectKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectKey is required"));
        }

        String dedupTarget = (proposalId != null ? proposalId : projectKey + ":" + tickets.size());
        AsyncJob job = asyncJobService.startOrAttach(
                "TICKET_CREATION", "PROPOSAL", dedupTarget, null);
        if (!job.attachedToExisting()) {
            ticketCreationService.createTicketsAsync(projectKey, tickets, job.id());
            if (proposal != null) {
                proposal.setStatus(com.assurant.brain.enums.TicketProposalStatus.CREATED.name());
                ticketProposalRepository.save(proposal);
            }
        }

        log.info("Ticket creation queued: project={} count={} job={}", projectKey, tickets.size(), job.id());

        return ResponseEntity.accepted().body(Map.of(
                "projectKey", projectKey,
                "ticketCount", tickets.size(),
                "jobId", job.id().toString(),
                "streamUrl", "/api/v1/jobs/stream/" + job.id(),
                "attachedToExisting", job.attachedToExisting()
        ));
    }

    @GetMapping
    public ResponseEntity<List<TicketProposal>> listProposals(
            @RequestParam(defaultValue = "default") String userId) {
        return ResponseEntity.ok(ticketProposalRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }
}
