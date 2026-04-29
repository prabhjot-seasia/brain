package com.assurant.brain.jira;

import com.assurant.brain.dto.request.AnalyzeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JiraIssueMapper")
class JiraIssueMapperTest {

    private final JiraIssueMapper mapper = new JiraIssueMapper();

    @Test
    @DisplayName("maps issue with ADF description to AnalyzeRequest")
    void mapsAdfDescription() {
        Map<String, Object> issue = Map.of(
                "key", "PAY-123",
                "fields", Map.of(
                        "summary", "Add payment retry logic",
                        "description", Map.of(
                                "type", "doc",
                                "content", List.of(
                                        Map.of("type", "paragraph",
                                                "content", List.of(
                                                        Map.of("type", "text", "text", "Retry failed payments after 30 minutes")
                                                ))
                                )
                        )
                )
        );

        AnalyzeRequest request = mapper.toAnalyzeRequest(issue, "proj-payments");

        assertThat(request.projectId()).isEqualTo("proj-payments");
        assertThat(request.requirement()).contains("Add payment retry logic");
        assertThat(request.requirement()).contains("Retry failed payments after 30 minutes");
    }

    @Test
    @DisplayName("maps issue with plain string description")
    void mapsStringDescription() {
        Map<String, Object> issue = Map.of(
                "key", "PAY-124",
                "fields", Map.of(
                        "summary", "Fix timeout bug",
                        "description", "The payment service times out after 5s"
                )
        );

        AnalyzeRequest request = mapper.toAnalyzeRequest(issue, "proj-payments");

        assertThat(request.requirement()).contains("Fix timeout bug");
        assertThat(request.requirement()).contains("times out after 5s");
    }

    @Test
    @DisplayName("maps issue with null description uses summary only")
    void mapsNullDescription() {
        Map<String, Object> issue = Map.of(
                "key", "PAY-125",
                "fields", Map.of("summary", "Quick fix needed")
        );

        AnalyzeRequest request = mapper.toAnalyzeRequest(issue, "proj-payments");

        assertThat(request.requirement()).isEqualTo("Quick fix needed");
    }

    @Test
    @DisplayName("extracts issue key")
    void extractsKey() {
        Map<String, Object> issue = Map.of("key", "BRAIN-42", "fields", Map.of());

        assertThat(mapper.extractIssueKey(issue)).isEqualTo("BRAIN-42");
    }

    @Test
    @DisplayName("extracts summary from fields")
    void extractsSummary() {
        Map<String, Object> issue = Map.of(
                "key", "X-1",
                "fields", Map.of("summary", "Implement caching")
        );

        assertThat(mapper.extractSummary(issue)).isEqualTo("Implement caching");
    }

    @Test
    @DisplayName("handles nested ADF with lists and headings")
    void handlesNestedAdf() {
        Map<String, Object> issue = Map.of(
                "key", "PAY-200",
                "fields", Map.of(
                        "summary", "Complex requirement",
                        "description", Map.of(
                                "type", "doc",
                                "content", List.of(
                                        Map.of("type", "heading",
                                                "content", List.of(
                                                        Map.of("type", "text", "text", "Acceptance Criteria")
                                                )),
                                        Map.of("type", "paragraph",
                                                "content", List.of(
                                                        Map.of("type", "text", "text", "Must support retry")
                                                )),
                                        Map.of("type", "listItem",
                                                "content", List.of(
                                                        Map.of("type", "text", "text", "Max 3 retries")
                                                ))
                                )
                        )
                )
        );

        AnalyzeRequest request = mapper.toAnalyzeRequest(issue, "proj-payments");

        assertThat(request.requirement()).contains("Acceptance Criteria");
        assertThat(request.requirement()).contains("Must support retry");
        assertThat(request.requirement()).contains("Max 3 retries");
    }
}
