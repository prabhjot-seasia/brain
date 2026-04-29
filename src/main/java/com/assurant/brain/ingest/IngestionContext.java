package com.assurant.brain.ingest;

import com.assurant.brain.dto.request.IngestManifest;
import com.assurant.brain.graph.node.ProjectNode;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;

public record IngestionContext(
        String projectId,
        Path projectPath,
        String repoUrl,
        ProjectNode projectNode,
        RepoKind repoKind,
        List<Document> documents,
        IngestManifest manifest) {

    public IngestionContext(String projectId, Path projectPath, String repoUrl,
                             ProjectNode projectNode, RepoKind repoKind, List<Document> documents) {
        this(projectId, projectPath, repoUrl, projectNode, repoKind, documents, IngestManifest.empty());
    }
}
