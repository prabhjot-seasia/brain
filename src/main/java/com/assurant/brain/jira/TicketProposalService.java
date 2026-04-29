package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.TicketProposalRepository;
import com.assurant.brain.domain.TicketProposal;
import com.assurant.brain.dto.request.ProposedTicket;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.enums.TicketProposalStatus;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class TicketProposalService {

    private static final String SYSTEM_PROMPT = """
            You are a senior product manager decomposing requirements into Jira tickets.

            Given a document (PRD, grooming notes, requirements, or meeting notes), produce
            a JSON array of proposed Jira tickets. Each ticket must have:

            {
              "title": "concise ticket title (under 80 chars)",
              "description": "detailed description in plain text",
              "acceptanceCriteria": "bullet-pointed acceptance criteria",
              "issueType": "Story" | "Task" | "Bug",
              "storyPoints": 1 | 2 | 3 | 5 | 8 | 13,
              "priority": "High" | "Medium" | "Low"
            }

            Rules:
            1. Each ticket must be independently implementable
            2. Tickets should be sequenced in dependency order (first ticket = no dependencies)
            3. Story points follow Fibonacci: 1 (trivial), 2 (small), 3 (medium), 5 (large), 8 (very large), 13 (epic-level, consider splitting)
            4. If a requirement is vague, create a Spike ticket (issueType: "Task") to investigate
            5. Include edge cases and error handling as acceptance criteria
            6. Return ONLY valid JSON array — no markdown fences, no explanation text
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final TicketProposalRepository ticketProposalRepository;
    private final AsyncJobService asyncJobService;

    public List<ProposedTicket> propose(String documentText) {
        log.info("Proposing tickets from document ({} chars)", documentText.length());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(documentText)
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("TicketProposalService", LlmOperation.TICKET_PROPOSE, null,
                documentText, rawResponse, latencyMs, false, modelName);

        return parseTickets(rawResponse);
    }

    @Async("brainLlmExecutor")
    public void proposeAsync(UUID proposalId) {
        proposeAsync(proposalId, null);
    }

    @Async("brainLlmExecutor")
    public void proposeAsync(UUID proposalId, UUID jobId) {
        TicketProposal record = ticketProposalRepository.findById(proposalId).orElse(null);
        if (record == null) {
            log.warn("Async ticket proposal: record {} not found", proposalId);
            if (jobId != null) asyncJobService.markFailed(jobId, "Proposal record not found");
            return;
        }

        if (jobId != null) asyncJobService.markRunning(jobId, "Decomposing into tickets");
        try {
            List<ProposedTicket> tickets = propose(record.getSourceText());
            record.setProposedTickets(tickets.stream()
                    .map(t -> objectMapper.convertValue(t,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}))
                    .collect(Collectors.toList()));
            record.setStatus(TicketProposalStatus.PROPOSED.name());
            ticketProposalRepository.save(record);
            if (jobId != null) asyncJobService.markSucceeded(jobId, Map.of(
                    "proposalId", proposalId.toString(),
                    "ticketCount", tickets.size()));
        } catch (Exception e) {
            log.error("Async ticket proposal failed for {}: {}", proposalId, e.getMessage(), e);
            record.setStatus(TicketProposalStatus.FAILED.name());
            ticketProposalRepository.save(record);
            if (jobId != null) asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private List<ProposedTicket> parseTickets(String rawJson) {
        String json = LlmJsonParser.stripFences(rawJson);

        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse ticket proposals from LLM response: {}", json, e);
            throw new IllegalStateException("Failed to parse proposed tickets: " + e.getMessage());
        }
    }
}
