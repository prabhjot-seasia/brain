package com.assurant.brain.ingest;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.enums.SourceType;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class IngestDocumentFactory {

    private final BrainProperties brainProperties;

    public Document buildDocChunk(String projectId, String filePath, ChunkType chunkType,
                                   String chunkName, String content) {
        return build(projectId, filePath, SourceType.DOC, chunkType, chunkName, null,
                content, docTrustWeight(), Map.of());
    }

    public Document buildDocChunk(String projectId, String filePath, ChunkType chunkType,
                                   String chunkName, String content, Map<String, Object> extraMetadata) {
        return build(projectId, filePath, SourceType.DOC, chunkType, chunkName, null,
                content, docTrustWeight(), extraMetadata);
    }

    private Document build(String projectId, String filePath,
                           SourceType sourceType, ChunkType chunkType,
                           String chunkName, String packageName,
                           String content, double trustWeight,
                           Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>(extraMetadata);
        metadata.put("projectId", projectId);
        metadata.put("filePath", filePath);
        metadata.put("sourceType", sourceType.name());
        metadata.put("chunkType", chunkType != null ? chunkType.name() : StringUtils.EMPTY);
        metadata.put("chunkName", StringUtils.defaultString(chunkName));
        metadata.put("packageName", StringUtils.defaultString(packageName));
        metadata.put("trustWeight", trustWeight);
        metadata.put("contentHash", sha256(content));
        return new Document(content, metadata);
    }

    private double docTrustWeight() {
        return brainProperties.rag() != null ? brainProperties.rag().docTrustWeight() : 1.5;
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
