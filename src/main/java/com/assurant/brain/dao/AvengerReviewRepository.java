package com.assurant.brain.dao;

import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AvengerReviewRepository extends JpaRepository<AvengerReview, UUID> {

    List<AvengerReview> findByAvengerAndProjectIdOrderByCreatedAtDesc(AvengerType avenger, String projectId, Pageable pageable);

    List<AvengerReview> findByProjectIdOrderByCreatedAtDesc(String projectId, Pageable pageable);
}
