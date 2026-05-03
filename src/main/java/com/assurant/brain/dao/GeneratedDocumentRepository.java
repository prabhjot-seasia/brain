package com.assurant.brain.dao;

import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {

    List<GeneratedDocument> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<GeneratedDocument> findByProjectIdAndDocTypeOrderByCreatedAtDesc(String projectId, DocType docType);

    @Query("SELECT d FROM generated_documents d WHERE d.projectId = :projectId "
            + "AND d.docType = :docType AND d.contentHash = :hash "
            + "AND d.generatedAt >= :since AND d.status IN (:statuses) "
            + "ORDER BY d.generatedAt DESC")
    List<GeneratedDocument> findCachedFullDoc(@Param("projectId") String projectId,
                                               @Param("docType") DocType docType,
                                               @Param("hash") String contentHash,
                                               @Param("since") OffsetDateTime since,
                                               @Param("statuses") List<DocGenerationStatus> statuses);

    @Query("SELECT d FROM generated_documents d WHERE d.projectId = :projectId "
            + "AND d.docType = :docType AND d.status = :status "
            + "ORDER BY d.createdAt DESC")
    Optional<GeneratedDocument> findFirstByProjectIdAndDocTypeAndStatus(@Param("projectId") String projectId,
                                                                        @Param("docType") DocType docType,
                                                                        @Param("status") DocGenerationStatus status);

    Optional<GeneratedDocument> findFirstByProjectIdAndConfluencePageIdIsNotNullOrderByConfluencePublishedAtDesc(String projectId);
}
