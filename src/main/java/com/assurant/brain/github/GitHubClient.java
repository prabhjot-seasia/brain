package com.assurant.brain.github;

import com.assurant.brain.config.properties.BrainProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Log4j2
@Component
public class GitHubClient {

    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GitHubClient(BrainProperties brainProperties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(brainProperties.github().apiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + brainProperties.github().token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String getDefaultBranchSha(String owner, String repo, String branch) {
        log.info("Fetching HEAD SHA for {}/{} branch={}", owner, repo, branch);

        String body = restClient.get()
                .uri("/repos/{owner}/{repo}/git/ref/heads/{branch}", owner, repo, branch)
                .retrieve()
                .body(String.class);

        return extractJsonField(body, "object", "sha");
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void createBranch(String owner, String repo, String branchName, String fromSha) {
        log.info("Creating branch {}/{} branch={} from SHA={}", owner, repo, branchName, fromSha);

        restClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .body(Map.of("ref", BRANCH_REF_PREFIX + branchName, "sha", fromSha))
                .retrieve()
                .toBodilessEntity();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void createOrUpdateFile(String owner, String repo, String branch,
                                    String filePath, String content, String commitMessage) {
        log.debug("Committing file {}/{} path={} on branch={}", owner, repo, filePath, branch);

        String existingSha = getExistingFileSha(owner, repo, branch, filePath);

        Map<String, Object> body = new HashMap<>(Map.of(
                "message", commitMessage,
                "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "branch", branch
        ));

        if (existingSha != null) {
            body.put("sha", existingSha);
        }

        restClient.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public PullRequestResult createPullRequest(String owner, String repo, String title,
                                                String body, String headBranch, String baseBranch) {
        log.info("Creating Draft PR {}/{} head={} base={}", owner, repo, headBranch, baseBranch);

        String response = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .body(Map.of(
                        "title", title,
                        "body", body,
                        "head", headBranch,
                        "base", baseBranch,
                        "draft", true
                ))
                .retrieve()
                .body(String.class);

        int prNumber = extractJsonIntField(response, "number");
        String prUrl = extractJsonStringField(response, "html_url");
        return new PullRequestResult(prNumber, prUrl);
    }

    private String getExistingFileSha(String owner, String repo, String branch, String filePath) {
        try {
            String body = restClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}?ref={branch}", owner, repo, filePath, branch)
                    .retrieve()
                    .body(String.class);
            return extractJsonStringField(body, "sha");
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonField(String json, String... path) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            Object current = map;
            for (String key : path) {
                @SuppressWarnings("unchecked")
                Map<String, Object> currentMap = (Map<String, Object>) current;
                current = currentMap.get(key);
            }
            return current != null ? current.toString() : null;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub API response: " + e.getMessage());
        }
    }

    private String extractJsonStringField(String json, String field) {
        return extractJsonField(json, field);
    }

    private int extractJsonIntField(String json, String field) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return ((Number) map.get(field)).intValue();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub API response: " + e.getMessage());
        }
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String getWorkflowRunLogs(String owner, String repo, long runId) {
        log.info("Fetching workflow run logs for {}/{} runId={}", owner, repo, runId);

        try {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}/actions/runs/{runId}/logs", owner, repo, runId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("Could not fetch raw logs for run={}, falling back to jobs API", runId);
            return getWorkflowRunJobsOutput(owner, repo, runId);
        }
    }

    private String getWorkflowRunJobsOutput(String owner, String repo, long runId) {
        String body = restClient.get()
                .uri("/repos/{owner}/{repo}/actions/runs/{runId}/jobs", owner, repo, runId)
                .retrieve()
                .body(String.class);
        return body != null ? body : "";
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String getPullRequestDiff(String owner, String repo, int prNumber) {
        log.info("Fetching PR diff for {}/{} #{}", owner, repo, prNumber);

        return restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    @Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public Map<String, Object> getPullRequestDetails(String owner, String repo, int prNumber) {
        log.info("Fetching PR details for {}/{} #{}", owner, repo, prNumber);

        String body = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                .retrieve()
                .body(String.class);

        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse PR details: " + e.getMessage());
        }
    }

    public record PullRequestResult(int prNumber, String prUrl) {}
}
