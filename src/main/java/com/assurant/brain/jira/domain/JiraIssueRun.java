package com.assurant.brain.jira.domain;

import com.assurant.brain.jira.enums.JiraRunState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "jira_issue_runs")
public class JiraIssueRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "issue_key", nullable = false, length = 64)
    private String issueKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 40)
    private JiraRunState state = JiraRunState.IDLE;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "pr_batch_id")
    private UUID prBatchId;

    @Column(name = "affinity_project_ids", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> affinityProjectIds;

    @Column(name = "last_label", length = 40)
    private String lastLabel;

    @Column(name = "last_transition_at", nullable = false)
    private OffsetDateTime lastTransitionAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastTransitionAt == null) lastTransitionAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
