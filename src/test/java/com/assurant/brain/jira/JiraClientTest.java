package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JiraClient — PAT auth")
class JiraClientTest {

    private JiraClient buildClient(BrainProperties.Jira jira) {
        BrainProperties props = new BrainProperties(null, null, null, null, null, jira,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new JiraClient(props, RestClient.builder());
    }

    @Test
    @DisplayName("throws when base-url is missing")
    void throwsWhenBaseUrlMissing() {
        JiraClient client = buildClient(new BrainProperties.Jira(
                "", "user@example.com", "token", "secret", "AI_DEV_"));

        assertThatThrownBy(() -> client.getIssue("PROJ-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("throws when email or api-token is missing")
    void throwsWhenCredentialsMissing() {
        JiraClient client = buildClient(new BrainProperties.Jira(
                "https://example.atlassian.net", "", "", "secret", "AI_DEV_"));

        assertThatThrownBy(() -> client.getIssue("PROJ-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("throws when jira config record is missing")
    void throwsWhenJiraNull() {
        BrainProperties props = new BrainProperties(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        JiraClient client = new JiraClient(props, RestClient.builder());

        assertThatThrownBy(() -> client.getIssue("PROJ-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brain.jira");
    }
}
