package com.assurant.brain.domain;

import com.assurant.brain.enums.ReviewVerdict;
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
@Entity(name = "code_review_iterations")
public class CodeReviewIteration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "pr_record_id", nullable = false)
    private UUID prRecordId;

    @Column(name = "iteration_number", nullable = false)
    private int iterationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 10)
    private ReviewVerdict verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues_found", columnDefinition = "jsonb")
    private List<String> issuesFound;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fixes_applied", columnDefinition = "jsonb")
    private List<String> fixesApplied;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
