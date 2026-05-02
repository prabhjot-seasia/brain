package com.assurant.brain.auth;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.UserSessionRepository;
import com.assurant.brain.domain.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JiraOAuthController")
class JiraOAuthControllerTest {

    private UserSessionRepository userSessionRepository;
    private JiraOAuthController controller;

    @BeforeEach
    void setup() {
        userSessionRepository = mock(UserSessionRepository.class);
        var tokenStore = mock(JiraTokenStore.class);
        var jira = new BrainProperties.Jira("client-id", "client-secret",
                "http://localhost:3000/auth/callback", "encryption-key-16chars!!",
                null, "AI_DEV_", null);
        var props = new BrainProperties(null, null, null, null, null, jira, null, null, null, null, null, null, null, null, null, null, null, null, null);
        controller = new JiraOAuthController(props, userSessionRepository, tokenStore,
                org.springframework.web.client.RestClient.builder());
    }

    @Test
    @DisplayName("connect returns authorization URL with state")
    void connectReturnsAuthUrl() {
        ResponseEntity<Map<String, String>> response = controller.connect();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("authUrl");
        assertThat(response.getBody()).containsKey("state");
        assertThat(response.getBody().get("authUrl")).contains("client_id=client-id");
        assertThat(response.getBody().get("authUrl")).contains("auth.atlassian.com");
    }

    @Test
    @DisplayName("status returns connected=true for active session")
    void statusConnected() {
        UserSession session = new UserSession();
        session.setUserId("user-1");
        session.setJiraAccessTokenEnc("enc-token");
        session.setJiraSiteUrl("https://mysite.atlassian.net");
        session.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.of(session));

        ResponseEntity<Map<String, Object>> response = controller.status("user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("connected", true);
        assertThat(response.getBody()).containsEntry("siteUrl", "https://mysite.atlassian.net");
    }

    @Test
    @DisplayName("status returns connected=false when no session")
    void statusNotConnected() {
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.status("user-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("connected", false);
    }

    @Test
    @DisplayName("status returns connected=false when session expired")
    void statusExpired() {
        UserSession session = new UserSession();
        session.setUserId("user-1");
        session.setJiraAccessTokenEnc("enc-token");
        session.setExpiresAt(OffsetDateTime.now().minusHours(1));
        when(userSessionRepository.findByUserId("user-1")).thenReturn(Optional.of(session));

        ResponseEntity<Map<String, Object>> response = controller.status("user-1");

        assertThat(response.getBody()).containsEntry("connected", false);
    }
}
