package com.assurant.brain.dao;

import com.assurant.brain.domain.SolutionPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SolutionPatternRepository extends JpaRepository<SolutionPattern, UUID> {

    List<SolutionPattern> findByProjectIdOrderBySuccessCountDesc(String projectId);

    List<SolutionPattern> findAllByOrderBySuccessCountDesc();
}
