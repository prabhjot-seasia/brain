package com.assurant.brain.dao;

import com.assurant.brain.domain.PrBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrBatchRepository extends JpaRepository<PrBatch, UUID> {
    List<PrBatch> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);
}
