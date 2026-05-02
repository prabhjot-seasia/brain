package com.assurant.brain.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PrCreationService")
class PrCreationServiceTest {

    private GitHubClient gitHubClient;
    private PrCreationService service;

    @BeforeEach
    void setup() {
        gitHubClient = mock(GitHubClient.class);
        service = new PrCreationService(gitHubClient);
    }

    @Test
    @DisplayName("createPullRequest orchestrates branch creation, file commits, and PR")
    void createPrHappyPath() {
        when(gitHubClient.getDefaultBranchSha("owner", "repo", "main")).thenReturn("abc123");
        doNothing().when(gitHubClient).createBranch(eq("owner"), eq("repo"), anyString(), eq("abc123"));
        doNothing().when(gitHubClient).createOrUpdateFile(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), anyString());
        when(gitHubClient.createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequestResult(42, "https://github.com/owner/repo/pull/42"));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/Foo.java", "class Foo {}");
        files.put("src/Bar.java", "class Bar {}");

        PrCreationService.PrResult result = service.createPullRequest(
                "https://github.com/owner/repo", "main", files, "Add feature X");

        assertThat(result.prNumber()).isEqualTo(42);
        assertThat(result.prUrl()).isEqualTo("https://github.com/owner/repo/pull/42");
        assertThat(result.branchName()).startsWith("brain/codegen-");

        verify(gitHubClient).getDefaultBranchSha("owner", "repo", "main");
        verify(gitHubClient).createBranch(eq("owner"), eq("repo"), anyString(), eq("abc123"));
        verify(gitHubClient, times(2)).createOrUpdateFile(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), anyString());
        verify(gitHubClient).createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main"));
    }

    @Test
    @DisplayName("createPullRequest handles .git suffix in URL")
    void createPrWithGitSuffix() {
        when(gitHubClient.getDefaultBranchSha("owner", "repo", "main")).thenReturn("sha1");
        when(gitHubClient.createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequestResult(1, "url"));

        service.createPullRequest("https://github.com/owner/repo.git", "main", Map.of("f.java", "c"), "plan");

        verify(gitHubClient).getDefaultBranchSha("owner", "repo", "main");
    }

    @Test
    @DisplayName("createPullRequest throws on invalid repo URL")
    void createPrInvalidUrl() {
        assertThatThrownBy(() -> service.createPullRequest(
                "not-a-url", "main", Map.of(), "plan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub repository URL");
    }

    @Test
    @DisplayName("createPullRequest with jiraIssueKey prefixes the title with [KEY]")
    void createPrWithJiraKey() {
        when(gitHubClient.getDefaultBranchSha("owner", "repo", "main")).thenReturn("sha");
        when(gitHubClient.createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequestResult(1, "url"));

        service.createPullRequest("https://github.com/owner/repo", "main",
                Map.of("f.java", "c"), "Add CSV export", "body", "ASSURE-123");

        verify(gitHubClient).createPullRequest(eq("owner"), eq("repo"),
                argThat(title -> title.startsWith("[ASSURE-123] brain: ")),
                anyString(), anyString(), eq("main"));
    }

    @Test
    @DisplayName("createPullRequest with null jiraIssueKey keeps the legacy 'brain:' prefix")
    void createPrWithoutJiraKey() {
        when(gitHubClient.getDefaultBranchSha("owner", "repo", "main")).thenReturn("sha");
        when(gitHubClient.createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequestResult(1, "url"));

        service.createPullRequest("https://github.com/owner/repo", "main",
                Map.of("f.java", "c"), "Add CSV export", "body", null);

        verify(gitHubClient).createPullRequest(eq("owner"), eq("repo"),
                argThat(title -> title.startsWith("brain: ") && !title.startsWith("[")),
                anyString(), anyString(), eq("main"));
    }

    @Test
    @DisplayName("createPullRequest truncates long plan summary in PR title")
    void createPrTruncatesTitle() {
        when(gitHubClient.getDefaultBranchSha("owner", "repo", "main")).thenReturn("sha");
        when(gitHubClient.createPullRequest(eq("owner"), eq("repo"), anyString(), anyString(), anyString(), eq("main")))
                .thenReturn(new GitHubClient.PullRequestResult(1, "url"));

        String longPlan = "A".repeat(100);
        service.createPullRequest("https://github.com/owner/repo", "main", Map.of("f.java", "c"), longPlan);

        verify(gitHubClient).createPullRequest(eq("owner"), eq("repo"),
                argThat(title -> title.length() <= 70), anyString(), anyString(), eq("main"));
    }
}
