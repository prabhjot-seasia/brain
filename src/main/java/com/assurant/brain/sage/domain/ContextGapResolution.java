package com.assurant.brain.sage.domain;

import com.assurant.brain.sage.AnswerSource;
import com.assurant.brain.sage.ContextGapType;
import com.assurant.brain.sage.GapStatus;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "context_gap_resolutions")
public class ContextGapResolution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false, length = 255)
    private String projectId;

    @Column(name = "gap_signature", nullable = false, length = 512)
    private String gapSignature;

    @Enumerated(EnumType.STRING)
    @Column(name = "gap_type", nullable = false, length = 60)
    private ContextGapType gapType;

    @Column(name = "tier", nullable = false)
    private short tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GapStatus status = GapStatus.PENDING;

    @Column(name = "question", nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "suggested_answer", columnDefinition = "text")
    private String suggestedAnswer;

    @Column(name = "suggested_choices", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> suggestedChoices;

    @Column(name = "answer_kind", length = 40)
    private String answerKind;

    @Column(name = "answer_value", columnDefinition = "text")
    private String answerValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_source", length = 20)
    private AnswerSource answerSource;

    @Column(name = "confidence", precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "asked_count", nullable = false)
    private int askedCount;

    @Column(name = "linked_issue_key", length = 64)
    private String linkedIssueKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
