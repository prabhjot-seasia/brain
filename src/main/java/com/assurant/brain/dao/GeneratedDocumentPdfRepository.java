package com.assurant.brain.dao;

import com.assurant.brain.domain.GeneratedDocumentPdf;
import com.assurant.brain.enums.DocType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedDocumentPdfRepository extends JpaRepository<GeneratedDocumentPdf, UUID> {

    Optional<GeneratedDocumentPdf> findByDocumentIdAndDocType(UUID documentId, DocType docType);

    List<GeneratedDocumentPdf> findByDocumentIdOrderByDocTypeAsc(UUID documentId);

    void deleteByDocumentIdAndDocType(UUID documentId, DocType docType);
}
