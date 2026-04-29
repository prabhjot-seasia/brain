package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.exceptions.IngestionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
@Service
@RequiredArgsConstructor
public class GitCloneService {

    private final BrainProperties brainProperties;

    public record CloneResult(Path directory, String commitSha) {}

    public CloneResult clone(String repoUrl, String branch) {
        Path tempDir = createTempDirectory();
        try {
            Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir.toFile())
                    .setBranch(branch)
                    .setDepth(1)
                    .setCredentialsProvider(credentialsProvider())
                    .call();

            ObjectId head = git.getRepository().resolve("HEAD");
            String sha = head != null ? head.getName() : "unknown";
            git.close();

            log.info("Cloned {} branch={} sha={}", repoUrl, branch, sha);
            return new CloneResult(tempDir, sha);
        } catch (GitAPIException e) {
            deletePath(tempDir);
            throw new IngestionException(resolveCloneError(e, repoUrl));
        } catch (IOException e) {
            deletePath(tempDir);
            throw new IngestionException("Failed to resolve HEAD commit: " + e.getMessage());
        }
    }

    private UsernamePasswordCredentialsProvider credentialsProvider() {
        String token = brainProperties.github() != null ? brainProperties.github().token() : null;
        if (token == null || token.isBlank()) {
            return new UsernamePasswordCredentialsProvider("", "");
        }
        return new UsernamePasswordCredentialsProvider(token, "");
    }

    String resolveCloneError(GitAPIException e, String repoUrl) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("not found") || msg.contains("404")) {
            return "Repository not found: " + repoUrl + ". Check the URL and ensure the PAT has repo access.";
        }
        if (msg.contains("Authentication") || msg.contains("401") || msg.contains("403")) {
            return "Authentication failed for " + repoUrl + ". Check BRAIN_GITHUB_TOKEN.";
        }
        if (msg.contains("timed out") || msg.contains("Connection refused")) {
            return "Cannot reach " + repoUrl + ". Check network connectivity.";
        }
        return "Git clone failed: " + msg;
    }

    private Path createTempDirectory() {
        try {
            return Files.createTempDirectory("brain-clone-");
        } catch (IOException e) {
            throw new IngestionException("Failed to create temp directory: " + e.getMessage());
        }
    }

    private void deletePath(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException e) { log.trace("Failed to delete temp file: {}", p, e); }
                            });
                }
            }
        } catch (IOException e) { log.trace("Failed to walk temp directory for cleanup: {}", path, e); }
    }

}
