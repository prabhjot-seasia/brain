package com.assurant.brain.dao;

import com.assurant.brain.domain.PullRequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PullRequestRecordRepository extends JpaRepository<PullRequestRecord, UUID> {

    List<PullRequestRecord> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    List<PullRequestRecord> findAllByOrderByCreatedAtDesc();

    List<PullRequestRecord> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
