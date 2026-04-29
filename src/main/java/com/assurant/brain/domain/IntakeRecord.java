package com.assurant.brain.domain;

import com.assurant.brain.enums.IntakeSourceType;
import com.assurant.brain.enums.IntakeStatus;
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
@Entity(name = "intake_records")
public class IntakeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private IntakeSourceType sourceType;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private IntakeStatus status = IntakeStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
