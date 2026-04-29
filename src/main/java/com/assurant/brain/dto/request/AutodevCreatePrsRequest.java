package com.assurant.brain.dto.request;

import java.util.List;

public record AutodevCreatePrsRequest(
        String sessionId,
        List<RepoSpec> repos
) {
    public record RepoSpec(String projectId, String repoUrl, String baseBranch) {}
}
