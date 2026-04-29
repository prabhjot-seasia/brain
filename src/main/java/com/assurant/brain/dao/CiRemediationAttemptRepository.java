package com.assurant.brain.dao;

import com.assurant.brain.domain.CiRemediationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CiRemediationAttemptRepository extends JpaRepository<CiRemediationAttempt, UUID> {

    List<CiRemediationAttempt> findByPrRecordIdOrderByAttemptNumberAsc(UUID prRecordId);

    int countByPrRecordId(UUID prRecordId);
}
