package com.assurant.brain.sage.dao;

import com.assurant.brain.sage.GapStatus;
import com.assurant.brain.sage.domain.ContextGapResolution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContextGapResolutionRepository extends JpaRepository<ContextGapResolution, UUID> {

    Optional<ContextGapResolution> findByProjectIdAndGapSignature(String projectId, String gapSignature);

    List<ContextGapResolution> findByProjectIdAndStatusInOrderByTierAscCreatedAtAsc(
            String projectId, List<GapStatus> statuses, Pageable pageable);

    List<ContextGapResolution> findByProjectIdAndStatusOrderByCreatedAtAsc(String projectId, GapStatus status);

    List<ContextGapResolution> findByLinkedIssueKey(String issueKey);
}
