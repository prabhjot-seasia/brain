package com.assurant.brain.domain;

import com.assurant.brain.enums.LlmOperation;
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

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "token_usage_records")
public class TokenUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "service_name", nullable = false, length = 50)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 30)
    private LlmOperation operation;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    @Column(name = "cached")
    private boolean cached;

    @Column(name = "latency_ms")
    private long latencyMs;

    @Column(name = "cost_estimate")
    private double costEstimate;

    @Column(name = "model_name", length = 50)
    private String modelName;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
