package com.assurant.brain.testing.dao;

import com.assurant.brain.testing.domain.TestScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestScenarioRepository extends JpaRepository<TestScenarioEntity, UUID> {

    List<TestScenarioEntity> findByIssueKeyOrderByCreatedAtAsc(String issueKey);

    Optional<TestScenarioEntity> findByIssueKeyAndScenarioId(String issueKey, String scenarioId);
}
