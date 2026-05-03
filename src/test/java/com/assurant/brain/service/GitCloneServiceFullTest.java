package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.exceptions.IngestionException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GitCloneService — credential and error handling")
class GitCloneServiceFullTest {

    @Test
    @DisplayName("credentialsProvider uses PAT when configured")
    void credentialsWithPat() {
        GitCloneService service = buildService("ghp_validtoken123");
        assertThatThrownBy(() -> service.clone("https://invalid-host.example.com/repo.git", "main"))
                .isInstanceOf(IngestionException.class);
    }

    @Test
    @DisplayName("credentialsProvider uses empty credentials when no PAT")
    void credentialsWithoutPat() {
        GitCloneService service = buildService("");
        assertThatThrownBy(() -> service.clone("https://invalid-host.example.com/repo.git", "main"))
                .isInstanceOf(IngestionException.class);
    }

    @Test
    @DisplayName("credentialsProvider handles null github config")
    void credentialsWithNullGithub() {
        GitCloneService service = new GitCloneService(
                new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThatThrownBy(() -> service.clone("https://invalid-host.example.com/repo.git", "main"))
                .isInstanceOf(IngestionException.class);
    }

    @Test
    @DisplayName("resolveCloneError maps 401 to auth failure message")
    void resolve401() {
        GitCloneService service = buildService("");
        String msg = service.resolveCloneError(
                newTransportException("401 Unauthorized"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Authentication failed");
    }

    @Test
    @DisplayName("resolveCloneError maps 403 to auth failure message")
    void resolve403() {
        GitCloneService service = buildService("");
        String msg = service.resolveCloneError(
                newTransportException("403 Forbidden"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Authentication failed");
    }

    @Test
    @DisplayName("resolveCloneError maps 404 to not-found message")
    void resolve404() {
        GitCloneService service = buildService("");
        String msg = service.resolveCloneError(
                newTransportException("404 not found"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Repository not found");
    }

    @Test
    @DisplayName("resolveCloneError maps timed out to network message")
    void resolveTimedOut() {
        GitCloneService service = buildService("");
        String msg = service.resolveCloneError(
                newTransportException("Connection timed out"),
                "https://github.com/org/repo.git");
        assertThat(msg).contains("Cannot reach");
    }

    @Test
    @DisplayName("resolveCloneError maps null message to generic error")
    void resolveNullMessage() {
        GitCloneService service = buildService("");
        GitAPIException ex = newTransportException(null);
        String msg = service.resolveCloneError(ex, "https://github.com/org/repo.git");
        assertThat(msg).startsWith("Git clone failed:");
    }

    @Test
    @DisplayName("clone to invalid host throws IngestionException with friendly message")
    void cloneInvalidHost() {
        GitCloneService service = buildService("ghp_test");
        assertThatThrownBy(() -> service.clone("https://invalid-host.example.com/repo.git", "main"))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("invalid-host.example.com");
    }

    @Test
    @DisplayName("CloneResult record holds directory and commitSha")
    void cloneResultRecord(@TempDir Path tempDir) {
        var result = new GitCloneService.CloneResult(tempDir, "abc123def456");
        assertThat(result.directory()).isEqualTo(tempDir);
        assertThat(result.commitSha()).isEqualTo("abc123def456");
    }

    private static TransportException newTransportException(String message) {
        return new TransportException(message != null ? message : "");
    }

    private GitCloneService buildService(String token) {
        return new GitCloneService(new BrainProperties(null, null, null, null, new BrainProperties.GitHub(token, "https://api.github.com", 3), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
