package com.assurant.brain.domain;

import com.assurant.brain.enums.IngestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Getter
@Setter
@ToString
@Entity(name = "projects")
public class Project implements Serializable {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "language")
    private String language;

    @Column(name = "framework")
    private String framework;

    @Column(name = "build_tool")
    private String buildTool;

    @Column(name = "description")
    private String description;

    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    @Column(name = "branch")
    private String branch;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "last_ingested")
    private OffsetDateTime lastIngested;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_status")
    private IngestionStatus ingestionStatus = IngestionStatus.PENDING;

    @Column(name = "ingestion_error")
    private String ingestionError;

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
