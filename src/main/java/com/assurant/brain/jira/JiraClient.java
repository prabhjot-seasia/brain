package com.assurant.brain.jira;

import com.assurant.brain.auth.JiraTokenStore;
import com.assurant.brain.dao.UserSessionRepository;
import com.assurant.brain.domain.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class JiraClient {

    private static final String JIRA_API_BASE = "https://api.atlassian.com/ex/jira";

    private final UserSessionRepository userSessionRepository;
    private final JiraTokenStore tokenStore;
    private final RestClient.Builder restClientBuilder;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getIssue(String userId, String issueKey) {
        RestClient client = authenticatedClient(userId);
        String cloudId = resolveCloudId(userId);

        return client.get()
                .uri(JIRA_API_BASE + "/{cloudId}/rest/api/3/issue/{issueKey}", cloudId, issueKey)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> searchIssues(String userId, String jql, int maxResults) {
        RestClient client = authenticatedClient(userId);
        String cloudId = resolveCloudId(userId);

        return client.get()
                .uri(JIRA_API_BASE + "/{cloudId}/rest/api/3/search?jql={jql}&maxResults={max}",
                        cloudId, jql, maxResults)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createIssue(String userId, Map<String, Object> fields) {
        RestClient client = authenticatedClient(userId);
        String cloudId = resolveCloudId(userId);

        return client.post()
                .uri(JIRA_API_BASE + "/{cloudId}/rest/api/3/issue", cloudId)
                .header("Content-Type", "application/json")
                .body(Map.of("fields", fields))
                .retrieve()
                .body(Map.class);
    }

    public void addComment(String userId, String issueKey, String commentBody) {
        RestClient client = authenticatedClient(userId);
        String cloudId = resolveCloudId(userId);

        Map<String, Object> adfBody = Map.of(
                "body", Map.of(
                        "version", 1,
                        "type", "doc",
                        "content", List.of(
                                Map.of("type", "paragraph",
                                        "content", List.of(
                                                Map.of("type", "text", "text", commentBody)
                                        ))
                        )
                )
        );

        client.post()
                .uri(JIRA_API_BASE + "/{cloudId}/rest/api/3/issue/{issueKey}/comment", cloudId, issueKey)
                .header("Content-Type", "application/json")
                .body(adfBody)
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient authenticatedClient(String userId) {
        UserSession session = userSessionRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No Jira session found for user: " + userId));

        if (!session.isJiraConnected()) {
            throw new IllegalStateException("Jira session expired for user: " + userId);
        }

        String accessToken = tokenStore.decrypt(session.getJiraAccessTokenEnc());

        return restClientBuilder.build().mutate()
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .build();
    }

    private String resolveCloudId(String userId) {
        return userSessionRepository.findByUserId(userId)
                .map(UserSession::getJiraCloudId)
                .orElseThrow(() -> new IllegalStateException("No Jira cloud ID for user: " + userId));
    }
}
