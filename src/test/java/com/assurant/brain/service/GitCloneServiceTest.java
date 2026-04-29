package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GitCloneService")
class GitCloneServiceTest {

    @Test
    @DisplayName("resolveCloneError: 404 maps to repository-not-found")
    void notFound() {
        var service = buildService("ghp_test");
        String msg = service.resolveCloneError(
                new TransportException("https://github.com/org/repo.git: not found"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Repository not found");
    }

    @Test
    @DisplayName("resolveCloneError: auth failure maps to check-token message")
    void authFailure() {
        var service = buildService("bad");
        String msg = service.resolveCloneError(
                new TransportException("Authentication failed for https://github.com/org/repo.git"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Authentication failed").contains("BRAIN_GITHUB_TOKEN");
    }

    @Test
    @DisplayName("resolveCloneError: connection refused maps to network message")
    void connectionRefused() {
        var service = buildService("");
        String msg = service.resolveCloneError(
                new TransportException("Connection refused"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Cannot reach");
    }

    @Test
    @DisplayName("resolveCloneError: unknown error includes raw message")
    void unknownError() {
        var service = buildService("");
        String msg = service.resolveCloneError(
                new TransportException("Something unexpected"),
                "https://github.com/org/repo.git");
        assertThat(msg).startsWith("Git clone failed:").contains("Something unexpected");
    }

    @Test
    @DisplayName("sha256 produces consistent 64-char hex digest")
    void sha256Consistency() {
        String hash1 = IngestionService.sha256("hello world");
        String hash2 = IngestionService.sha256("hello world");
        assertThat(hash1).isEqualTo(hash2).hasSize(64);
    }

    @Test
    @DisplayName("sha256 produces different hash for different content")
    void sha256Different() {
        assertThat(IngestionService.sha256("aaa")).isNotEqualTo(IngestionService.sha256("bbb"));
    }

    private GitCloneService buildService(String token) {
        return new GitCloneService(new BrainProperties(null, null, null, null,
                new BrainProperties.GitHub(token, "https://api.github.com", 3), null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
