package com.assurant.brain.dao;

import com.assurant.brain.domain.CodeReviewIteration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CodeReviewIterationRepository extends JpaRepository<CodeReviewIteration, UUID> {

    List<CodeReviewIteration> findByPrRecordIdOrderByIterationNumberAsc(UUID prRecordId);
}
