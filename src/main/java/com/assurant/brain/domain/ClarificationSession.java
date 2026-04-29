package com.assurant.brain.domain;

import com.assurant.brain.enums.SessionStatus;
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
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@ToString
@Entity(name = "clarification_sessions")
public class ClarificationSession implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "requirement", nullable = false)
    private String requirement;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rounds", columnDefinition = "jsonb")
    private List<Map<String, Object>> rounds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SessionStatus status = SessionStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_plan", columnDefinition = "jsonb")
    private Map<String, Object> finalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_projects", columnDefinition = "jsonb")
    private List<Map<String, Object>> affectedProjects;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_graph_json", columnDefinition = "jsonb")
    private Map<String, Object> planGraphJson;

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
