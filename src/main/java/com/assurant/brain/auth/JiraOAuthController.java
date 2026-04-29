package com.assurant.brain.auth;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.UserSessionRepository;
import com.assurant.brain.domain.UserSession;
import com.assurant.brain.dto.request.JiraCallbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@RestController
@RequestMapping("/api/v1/auth/jira")
@RequiredArgsConstructor
public class JiraOAuthController {

    private static final String ATLASSIAN_AUTH_URL = "https://auth.atlassian.com/authorize";
    private static final String ATLASSIAN_TOKEN_URL = "https://auth.atlassian.com/oauth/token";
    private static final String ATLASSIAN_RESOURCES_URL = "https://api.atlassian.com/oauth/token/accessible-resources";
    private static final String SCOPES = "read:jira-work write:jira-work read:jira-user offline_access";

    private final BrainProperties brainProperties;
    private final UserSessionRepository userSessionRepository;
    private final JiraTokenStore tokenStore;
    private final RestClient.Builder restClientBuilder;

    @GetMapping("/connect")
    public ResponseEntity<Map<String, String>> connect() {
        BrainProperties.Jira jira = brainProperties.jira();
        String state = UUID.randomUUID().toString();

        String authUrl = ATLASSIAN_AUTH_URL
                + "?audience=api.atlassian.com"
                + "&client_id=" + jira.oauthClientId()
                + "&scope=" + SCOPES.replace(" ", "%20")
                + "&redirect_uri=" + jira.oauthRedirectUri()
                + "&state=" + state
                + "&response_type=code"
                + "&prompt=consent";

        return ResponseEntity.ok(Map.of("authUrl", authUrl, "state", state));
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(@Valid @RequestBody JiraCallbackRequest body) {
        String code = body.code();
        String userId = body.effectiveUserId();

        BrainProperties.Jira jira = brainProperties.jira();

        RestClient client = restClientBuilder.build();

        @SuppressWarnings("unchecked")
        Map<String, Object> tokenResponse = client.post()
                .uri(ATLASSIAN_TOKEN_URL)
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "grant_type", "authorization_code",
                        "client_id", jira.oauthClientId(),
                        "client_secret", jira.oauthClientSecret(),
                        "code", code,
                        "redirect_uri", jira.oauthRedirectUri()
                ))
                .retrieve()
                .body(Map.class);

        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        int expiresIn = (int) tokenResponse.get("expires_in");

        var resources = client.get()
                .uri(ATLASSIAN_RESOURCES_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(List.class);

        Map<String, Object> site = (Map<String, Object>) resources.get(0);
        String cloudId = (String) site.get("id");
        String siteUrl = (String) site.get("url");

        UserSession session = userSessionRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserSession s = new UserSession();
                    s.setUserId(userId);
                    return s;
                });

        session.setJiraAccessTokenEnc(tokenStore.encrypt(accessToken));
        session.setJiraRefreshTokenEnc(refreshToken != null ? tokenStore.encrypt(refreshToken) : null);
        session.setJiraCloudId(cloudId);
        session.setJiraSiteUrl(siteUrl);
        session.setConnectedAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn));
        userSessionRepository.save(session);

        log.info("Jira OAuth connected for user={}, site={}, cloudId={}", userId, siteUrl, cloudId);

        return ResponseEntity.ok(Map.of(
                "connected", true,
                "siteUrl", siteUrl,
                "cloudId", cloudId
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam(defaultValue = "default") String userId) {
        return userSessionRepository.findByUserId(userId)
                .filter(UserSession::isJiraConnected)
                .map(s -> ResponseEntity.ok(Map.<String, Object>of(
                        "connected", true,
                        "siteUrl", s.getJiraSiteUrl() != null ? s.getJiraSiteUrl() : "",
                        "expiresAt", s.getExpiresAt() != null ? s.getExpiresAt().toString() : ""
                )))
                .orElse(ResponseEntity.ok(Map.of("connected", false)));
    }
}
