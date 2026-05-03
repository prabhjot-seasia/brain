package com.assurant.brain.domain;

import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
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

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "generated_documents")
public class GeneratedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 50)
    private DocType docType;

    @Column(name = "prompt", columnDefinition = "text")
    private String prompt;

    @Column(name = "content_md", columnDefinition = "text")
    private String contentMd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocGenerationStatus status = DocGenerationStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "content_pdf")
    private byte[] contentPdf;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "section_results", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String sectionResults;

    @Column(name = "section_contents", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String sectionContents;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;

    @Column(name = "confluence_page_id", length = 64)
    private String confluencePageId;

    @Column(name = "confluence_url", length = 2000)
    private String confluenceUrl;

    @Column(name = "confluence_space_key", length = 64)
    private String confluenceSpaceKey;

    @Column(name = "confluence_parent_page_id", length = 64)
    private String confluenceParentPageId;

    @Column(name = "confluence_published_at")
    private OffsetDateTime confluencePublishedAt;

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
