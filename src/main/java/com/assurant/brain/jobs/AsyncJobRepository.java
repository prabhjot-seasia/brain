package com.assurant.brain.jobs;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AsyncJobRepository extends JpaRepository<AsyncJobEntity, UUID> {

    Optional<AsyncJobEntity> findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(
            String jobType, String targetKind, String targetId, List<AsyncJobStatus> statuses);

    List<AsyncJobEntity> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<AsyncJobEntity> findByStatusInOrderByCreatedAtDesc(
            List<AsyncJobStatus> statuses, Pageable pageable);

    @Modifying
    @Query("delete from AsyncJobEntity j where j.status in :statuses and j.finishedAt < :cutoff")
    int deleteTerminalOlderThan(@Param("statuses") List<AsyncJobStatus> statuses,
                                 @Param("cutoff") OffsetDateTime cutoff);
}
