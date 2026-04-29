package com.assurant.brain.dao;

import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.AvengerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LearningEventRepository extends JpaRepository<LearningEvent, UUID> {

    List<LearningEvent> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<LearningEvent> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);

    List<LearningEvent> findByPrRecordIdOrderByCreatedAtDesc(UUID prRecordId);

    List<LearningEvent> findAllByOrderByCreatedAtDesc();

    List<LearningEvent> findByAvengerAndProjectIdOrderByCreatedAtDesc(
            AvengerType avenger, String projectId, Pageable pageable);
}
