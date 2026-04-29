package com.assurant.brain.domain;

import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.enums.SourceType;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@ToString(exclude = "embedding")
@Entity(name = "chunks")
public class Chunk implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type")
    private ChunkType chunkType;

    @Column(name = "chunk_name")
    private String chunkName;

    @Column(name = "package")
    private String packageName;

    @Column(name = "content", nullable = false)
    private String content;

    @Basic(fetch = jakarta.persistence.FetchType.LAZY)
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    private float[] embedding;

    @Column(name = "trust_weight", nullable = false)
    private double trustWeight = 1.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
