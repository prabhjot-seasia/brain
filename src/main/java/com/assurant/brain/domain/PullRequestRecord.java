package com.assurant.brain.domain;

import com.assurant.brain.enums.PrStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "pull_request_records")
public class PullRequestRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "base_branch", nullable = false, length = 100)
    private String baseBranch;

    @Column(name = "branch_name", length = 200)
    private String branchName;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url")
    private String prUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PrStatus status = PrStatus.GENERATING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generated_files", columnDefinition = "jsonb")
    private Map<String, String> generatedFiles;

    @Column(name = "self_review_iterations")
    private int selfReviewIterations;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "project_id", length = 255)
    private String projectId;

    @Column(name = "failure_stage", length = 32)
    private String failureStage;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
