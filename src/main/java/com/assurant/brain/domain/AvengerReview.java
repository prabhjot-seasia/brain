package com.assurant.brain.domain;

import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "avenger_reviews")
public class AvengerReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "avenger", nullable = false, length = 16)
    private AvengerType avenger;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private AvengerVerdict verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues", columnDefinition = "jsonb")
    private List<String> issues;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "tokens_in")
    private int tokensIn;

    @Column(name = "tokens_out")
    private int tokensOut;

    @Column(name = "latency_ms")
    private long latencyMs;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
