package com.assurant.brain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "ticket_proposals")
public class TicketProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "source_text", columnDefinition = "text")
    private String sourceText;

    @Column(name = "jira_project_key", nullable = false, length = 20)
    private String jiraProjectKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_tickets", columnDefinition = "jsonb")
    private List<Map<String, Object>> proposedTickets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "created_ticket_keys", columnDefinition = "jsonb")
    private List<String> createdTicketKeys;

    @Column(name = "status", length = 20)
    private String status = com.assurant.brain.enums.TicketProposalStatus.PROPOSED.name();

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}
