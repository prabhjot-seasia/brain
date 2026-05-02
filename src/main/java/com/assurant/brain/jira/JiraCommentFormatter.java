package com.assurant.brain.jira;

import com.assurant.brain.dto.response.ClarificationResponse.ClarificationQuestion;
import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.facade.autodev.MultiRepoPrResult;
import com.assurant.brain.facade.autodev.PerRepoResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class JiraCommentFormatter {

    public Map<String, Object> questions(List<ClarificationQuestion> questions, String labelPrefix) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(heading("Brain needs more info"));
        if (questions != null && !questions.isEmpty()) {
            content.add(orderedList(questions.stream().map(ClarificationQuestion::text).toList()));
        } else {
            content.add(paragraph("No specific questions extracted, but Brain wants to confirm scope before proceeding."));
        }
        content.add(paragraph("Reply in this thread, then transition the ticket to "
                + labelPrefix + "READY to continue."));
        return doc(content);
    }

    public Map<String, Object> planSummary(String summary, List<String> affectedProjects, String labelPrefix) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(heading("Brain's implementation plan"));
        if (summary != null && !summary.isBlank()) content.add(paragraph(summary));
        if (affectedProjects != null && !affectedProjects.isEmpty()) {
            content.add(paragraph("Repos that will receive Draft PRs:"));
            content.add(bulletList(affectedProjects));
        }
        content.add(paragraph("If this looks right, transition the ticket to " + labelPrefix
                + "READY to implement. If not, comment your changes and transition to "
                + labelPrefix + "ANALYSIS to redo planning."));
        return doc(content);
    }

    public Map<String, Object> prLinks(MultiRepoPrResult batch) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(heading("Draft PRs created"));
        if (batch == null || batch.perRepo() == null || batch.perRepo().isEmpty()) {
            content.add(paragraph("No PRs were created in this batch."));
            return doc(content);
        }
        List<String> lines = new ArrayList<>();
        for (PerRepoResult r : batch.perRepo()) {
            String marker = r.outcome() == PerRepoOutcome.SUCCESS ? "✓"
                    : r.outcome() == PerRepoOutcome.FAILED ? "✗" : "•";
            String url = r.prUrl() == null ? "(not created)" : r.prUrl();
            lines.add(marker + " " + r.projectId() + " — " + url);
        }
        content.add(bulletList(lines));
        content.add(paragraph("Total " + batch.totalRepos()
                + " · success " + batch.succeeded()
                + " · failed " + batch.failed()
                + " · skipped " + batch.skipped()));
        return doc(content);
    }

    public Map<String, Object> noAffinityFound(String labelPrefix) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(heading("Brain cannot identify the target repo"));
        content.add(paragraph("Affinity detection found no Brain-managed projects matching this ticket's text."));
        content.add(paragraph("Add a label like brain:<projectId> (e.g. brain:ce-imei) to override, "
                + "then transition the ticket to " + labelPrefix + "READY."));
        return doc(content);
    }

    public Map<String, Object> failure(Throwable t) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(heading("Brain run failed"));
        content.add(paragraph(safeFailureMessage(t)));
        content.add(paragraph("Brain will not advance the ticket state. Check Brain server logs for details."));
        return doc(content);
    }

    private String safeFailureMessage(Throwable t) {
        if (t == null) return "Unknown failure.";
        String type = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return "Failed: " + type;
        int newline = msg.indexOf('\n');
        String firstLine = newline > 0 ? msg.substring(0, newline) : msg;
        String stripped = firstLine.replaceAll("(?:[a-zA-Z_][\\w$]*\\.)+[A-Z][\\w$]*", "<class>");
        return "Failed (" + type + "): " + (stripped.length() > 240 ? stripped.substring(0, 240) + "…" : stripped);
    }

    private Map<String, Object> doc(List<Map<String, Object>> content) {
        return Map.of("version", 1, "type", "doc", "content", content);
    }

    private Map<String, Object> heading(String text) {
        return Map.of("type", "heading", "attrs", Map.of("level", 3),
                "content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> paragraph(String text) {
        return Map.of("type", "paragraph",
                "content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> orderedList(List<String> items) {
        List<Map<String, Object>> listItems = items.stream()
                .map(item -> Map.<String, Object>of("type", "listItem",
                        "content", List.of(paragraph(item))))
                .toList();
        return Map.of("type", "orderedList", "content", listItems);
    }

    private Map<String, Object> bulletList(List<String> items) {
        List<Map<String, Object>> listItems = items.stream()
                .map(item -> Map.<String, Object>of("type", "listItem",
                        "content", List.of(paragraph(item))))
                .toList();
        return Map.of("type", "bulletList", "content", listItems);
    }
}
