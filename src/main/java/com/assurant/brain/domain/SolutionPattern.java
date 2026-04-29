package com.assurant.brain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "solution_patterns")
public class SolutionPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "requirement_summary", columnDefinition = "text", nullable = false)
    private String requirementSummary;

    @Column(name = "plan_summary", columnDefinition = "text")
    private String planSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "code_patterns", columnDefinition = "jsonb")
    private Map<String, String> codePatterns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conventions_used", columnDefinition = "jsonb")
    private List<String> conventionsUsed;

    @Column(name = "success_count")
    private int successCount;

    @Column(name = "usage_count")
    private int usageCount;

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
