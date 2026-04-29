package com.assurant.brain.dao;

import com.assurant.brain.domain.IntakeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IntakeRecordRepository extends JpaRepository<IntakeRecord, UUID> {

    List<IntakeRecord> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
