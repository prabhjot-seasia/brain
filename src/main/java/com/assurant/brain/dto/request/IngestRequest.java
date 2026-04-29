package com.assurant.brain.dto.request;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

public record IngestRequest(
        @NotBlank String projectId,
        @NotBlank String projectName,
        @NotBlank @URL String repoUrl,
        String branch,
        String description,
        IngestManifest manifest
) {
    public IngestRequest(String projectId, String projectName, String repoUrl,
                         String branch, String description) {
        this(projectId, projectName, repoUrl, branch, description, null);
    }

    public String effectiveBranch() {
        return branch != null && !branch.isBlank() ? branch : "master";
    }

    public IngestManifest effectiveManifest() {
        return manifest != null ? manifest : IngestManifest.empty();
    }
}
