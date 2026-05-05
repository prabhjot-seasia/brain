package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class JiraClient {

    private final BrainProperties brainProperties;
    private final RestClient.Builder restClientBuilder;

    private volatile RestClient cachedClient;
    private volatile String cachedBaseUrl;

    @SuppressWarnings("unchecked")
    public Map<String, Object> getIssue(String issueKey) {
        return authenticatedClient().get()
                .uri(baseUrl() + "/rest/api/3/issue/{issueKey}", issueKey)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getIssueWithComments(String issueKey) {
        return authenticatedClient().get()
                .uri(baseUrl() + "/rest/api/3/issue/{issueKey}?fields=*all,comment&expand=renderedFields", issueKey)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> searchIssues(String jql, int maxResults) {
        return authenticatedClient().get()
                .uri(baseUrl() + "/rest/api/3/search?jql={jql}&maxResults={max}", jql, maxResults)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createIssue(Map<String, Object> fields) {
        return authenticatedClient().post()
                .uri(baseUrl() + "/rest/api/3/issue")
                .header("Content-Type", "application/json")
                .body(Map.of("fields", fields))
                .retrieve()
                .body(Map.class);
    }

    public void addComment(String issueKey, String commentBody) {
        addCommentAdf(issueKey, Map.of(
                "version", 1,
                "type", "doc",
                "content", List.of(
                        Map.of("type", "paragraph",
                                "content", List.of(
                                        Map.of("type", "text", "text", commentBody)
                                ))
                )));
    }

    public void addCommentAdf(String issueKey, Map<String, Object> adfDoc) {
        authenticatedClient().post()
                .uri(baseUrl() + "/rest/api/3/issue/{issueKey}/comment", issueKey)
                .header("Content-Type", "application/json")
                .body(Map.of("body", adfDoc))
                .retrieve()
                .toBodilessEntity();
    }

    public void transitionLabels(String issueKey,
                                  List<String> addLabels, List<String> removeLabels) {
        List<Map<String, Object>> labelOps = new java.util.ArrayList<>();
        if (addLabels != null) {
            for (String l : addLabels) {
                if (l != null && !l.isBlank()) labelOps.add(Map.of("add", l));
            }
        }
        if (removeLabels != null) {
            for (String l : removeLabels) {
                if (l != null && !l.isBlank()) labelOps.add(Map.of("remove", l));
            }
        }
        if (labelOps.isEmpty()) return;

        Map<String, Object> body = Map.of("update", Map.of("labels", labelOps));

        authenticatedClient().put()
                .uri(baseUrl() + "/rest/api/3/issue/{issueKey}", issueKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient authenticatedClient() {
        RestClient local = cachedClient;
        if (local == null) {
            synchronized (this) {
                local = cachedClient;
                if (local == null) {
                    local = restClientBuilder.build().mutate()
                            .defaultHeader("Authorization", "Basic " + basicAuthHeader())
                            .build();
                    cachedClient = local;
                }
            }
        }
        return local;
    }

    private String basicAuthHeader() {
        BrainProperties.Jira jira = jira();
        String email = jira.email();
        String token = jira.apiToken();
        if (email == null || email.isBlank() || token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "brain.jira.email and brain.jira.api-token must be configured for Jira PAT auth");
        }
        return Base64.getEncoder().encodeToString(
                (email + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        String local = cachedBaseUrl;
        if (local == null) {
            synchronized (this) {
                local = cachedBaseUrl;
                if (local == null) {
                    String url = jira().baseUrl();
                    if (url == null || url.isBlank()) {
                        throw new IllegalStateException("brain.jira.base-url is not configured");
                    }
                    local = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                    cachedBaseUrl = local;
                }
            }
        }
        return local;
    }

    private BrainProperties.Jira jira() {
        BrainProperties.Jira jira = brainProperties.jira();
        if (jira == null) {
            throw new IllegalStateException("brain.jira config is missing");
        }
        return jira;
    }
}
