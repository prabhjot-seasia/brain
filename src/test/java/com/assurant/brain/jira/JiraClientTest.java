package com.assurant.brain.jira;

import com.assurant.brain.auth.JiraTokenStore;
import com.assurant.brain.dao.UserSessionRepository;
import com.assurant.brain.domain.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JiraClient")
class JiraClientTest {

    private UserSessionRepository userSessionRepository;
    private JiraTokenStore tokenStore;
    private JiraClient client;

    @BeforeEach
    void setup() {
        userSessionRepository = mock(UserSessionRepository.class);
        tokenStore = mock(JiraTokenStore.class);
        var restClientBuilder = org.springframework.web.client.RestClient.builder();
        client = new JiraClient(userSessionRepository, tokenStore, restClientBuilder);
    }

    @Test
    @DisplayName("throws when user has no Jira session")
    void throwsWhenNoSession() {
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.getIssue("user-1", "PROJ-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Jira session found");
    }

    @Test
    @DisplayName("throws when Jira session is expired")
    void throwsWhenExpired() {
        UserSession session = new UserSession();
        session.setUserId("user-1");
        session.setJiraAccessTokenEnc(null);
        session.setExpiresAt(OffsetDateTime.now().minusHours(1));
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> client.getIssue("user-1", "PROJ-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("throws when no cloud ID found for resolveCloudId")
    void throwsWhenNoCloudId() {
        UserSession session = new UserSession();
        session.setUserId("user-1");
        session.setJiraAccessTokenEnc("encrypted-token");
        session.setExpiresAt(OffsetDateTime.now().plusHours(1));
        session.setJiraCloudId(null);
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.of(session));
        when(tokenStore.decrypt("encrypted-token")).thenReturn("real-token");

        assertThatThrownBy(() -> client.searchIssues("user-1", "project = PROJ", 10))
                .isInstanceOf(Exception.class);
    }
}
