package com.assurant.brain.domain;

import com.assurant.brain.enums.RemediationStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "ci_remediation_attempts")
public class CiRemediationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "pr_record_id", nullable = false)
    private UUID prRecordId;

    @Column(name = "workflow_run_id")
    private Long workflowRunId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RemediationStatus status = RemediationStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_summary", columnDefinition = "jsonb")
    private List<String> failureSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fixes_applied", columnDefinition = "jsonb")
    private List<String> fixesApplied;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
