package com.assurant.brain.jira;

import com.assurant.brain.dto.request.AnalyzeRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class JiraIssueMapper {

    @SuppressWarnings("unchecked")
    public AnalyzeRequest toAnalyzeRequest(Map<String, Object> issue, String projectId) {
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        String summary = (String) fields.getOrDefault("summary", "");
        String description = extractPlainText(fields.get("description"));

        String requirement = summary;
        if (!description.isBlank()) {
            requirement = summary + "\n\n" + description;
        }

        return new AnalyzeRequest(projectId, requirement, null, null);
    }

    public String extractIssueKey(Map<String, Object> issue) {
        return (String) issue.get("key");
    }

    @SuppressWarnings("unchecked")
    public String extractSummary(Map<String, Object> issue) {
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        return (String) fields.getOrDefault("summary", "");
    }

    @SuppressWarnings("unchecked")
    private String extractPlainText(Object adfNode) {
        if (adfNode == null) return "";
        if (adfNode instanceof String s) return s;
        if (!(adfNode instanceof Map<?, ?> node)) return "";

        Map<String, Object> map = (Map<String, Object>) node;
        String type = (String) map.getOrDefault("type", "");

        if ("text".equals(type)) {
            return (String) map.getOrDefault("text", "");
        }

        List<Object> content = (List<Object>) map.get("content");
        if (content == null) return "";

        StringBuilder sb = new StringBuilder();
        for (Object child : content) {
            String childText = extractPlainText(child);
            if (!childText.isBlank()) {
                if ("paragraph".equals(type) || "heading".equals(type)) {
                    sb.append(childText).append("\n");
                } else if ("listItem".equals(type)) {
                    sb.append("- ").append(childText).append("\n");
                } else {
                    sb.append(childText);
                }
            }
        }
        return sb.toString();
    }
}
