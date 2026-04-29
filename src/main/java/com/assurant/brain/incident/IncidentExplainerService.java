package com.assurant.brain.incident;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class IncidentExplainerService {

    private static final String SYSTEM_PROMPT = """
            You are an SRE writing a postmortem-style incident summary for a developer.
            Use this structure:
            ### Summary
            One-paragraph plain-English description of what failed and impact.
            ### Root cause
            Causal chain. Reference specific class names if known.
            ### Mitigation
            What was done. If unknown, say so.
            ### Prevention
            Concrete code or process changes that would prevent recurrence.
            Be terse. No filler.
            """;

    private static final int MAX_INCIDENTS_USED = 5;

    private final ChatModel chatModel;
    private final IncidentNodeRepository incidentNodeRepository;
    private final TokenUsageTracker tokenUsageTracker;
    private final RailChain railChain;
    private final BrainProperties brainProperties;

    public String explainIncident(String projectId, String classHint) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (classHint == null || classHint.isBlank()) {
            throw new IllegalArgumentException("classHint is required");
        }
        log.info("Explaining incidents for project={} classHint={}", projectId, classHint);

        List<IncidentNode> incidents = incidentNodeRepository
                .findByProjectIdAndClassHintMatch(projectId, classHint);
        if (incidents.isEmpty()) {
            return "### Summary\nNo incidents recorded for `" + classHint + "` in project `" + projectId + "`.";
        }

        String userPrompt = buildUserPrompt(projectId, classHint, incidents);
        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(projectId, "IncidentExplainerService", userPrompt));
        String sanitized = preLlm.sanitized();

        long startMs = System.currentTimeMillis();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(sanitized)));
        String response = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("IncidentExplainerService", LlmOperation.EXPLAIN, projectId,
                sanitized, response, latencyMs, false, modelName);
        return response;
    }

    private String buildUserPrompt(String projectId, String classHint, List<IncidentNode> incidents) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(projectId).append('\n');
        sb.append("Class hint: ").append(classHint).append('\n');
        sb.append("\nIncidents (most recent first):\n");
        int count = 0;
        for (IncidentNode i : incidents) {
            if (count++ >= MAX_INCIDENTS_USED) break;
            sb.append("- [")
                    .append(i.getSeverity() == null ? "INCIDENT" : i.getSeverity())
                    .append(" / ").append(i.getStatus() == null ? "?" : i.getStatus())
                    .append(" / ").append(i.getOccurredAt() == null ? "?" : i.getOccurredAt())
                    .append("] ").append(i.getTitle()).append('\n');
            if (i.getRootCauseSummary() != null && !i.getRootCauseSummary().isBlank()) {
                sb.append("  root cause: ").append(i.getRootCauseSummary()).append('\n');
            }
            if (i.getAffectedClassHints() != null && !i.getAffectedClassHints().isEmpty()) {
                sb.append("  affected classes: ").append(String.join(", ", i.getAffectedClassHints())).append('\n');
            }
        }
        sb.append("\nWrite the postmortem-style summary now.");
        return sb.toString();
    }
}
