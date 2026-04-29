package com.assurant.brain.domain;

import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.LearningEventType;
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
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "learning_events")
public class LearningEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "pr_record_id")
    private UUID prRecordId;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private LearningEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "avenger_id", length = 16)
    private AvengerType avenger;

    @Column(name = "convention_rule", columnDefinition = "text")
    private String conventionRule;

    @Column(name = "old_weight")
    private Double oldWeight;

    @Column(name = "new_weight")
    private Double newWeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
