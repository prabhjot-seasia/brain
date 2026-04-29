package com.assurant.brain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Entity(name = "user_sessions")
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "jira_access_token_enc")
    private String jiraAccessTokenEnc;

    @Column(name = "jira_refresh_token_enc")
    private String jiraRefreshTokenEnc;

    @Column(name = "jira_cloud_id")
    private String jiraCloudId;

    @Column(name = "jira_site_url")
    private String jiraSiteUrl;

    @Column(name = "connected_at")
    private OffsetDateTime connectedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }

    public boolean isJiraConnected() {
        return jiraAccessTokenEnc != null && (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now()));
    }
}
