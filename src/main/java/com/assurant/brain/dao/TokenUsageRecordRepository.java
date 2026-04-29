package com.assurant.brain.dao;

import com.assurant.brain.domain.TokenUsageRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TokenUsageRecordRepository extends JpaRepository<TokenUsageRecord, UUID> {

    List<TokenUsageRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<TokenUsageRecord> findByProjectIdOrderByCreatedAtDesc(String projectId);

    @Query("SELECT t FROM token_usage_records t WHERE t.createdAt >= :since ORDER BY t.createdAt DESC")
    List<TokenUsageRecord> findSince(OffsetDateTime since);

    @Query("SELECT t.serviceName, t.operation, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t), SUM(t.costEstimate) " +
            "FROM token_usage_records t WHERE t.createdAt >= :since GROUP BY t.serviceName, t.operation")
    List<Object[]> aggregateSince(OffsetDateTime since);

    @Query("SELECT COUNT(t) FROM token_usage_records t WHERE t.cached = true AND t.createdAt >= :since")
    long countCacheHitsSince(OffsetDateTime since);

    @Query("SELECT COUNT(t) FROM token_usage_records t WHERE t.createdAt >= :since")
    long countTotalSince(OffsetDateTime since);
}
