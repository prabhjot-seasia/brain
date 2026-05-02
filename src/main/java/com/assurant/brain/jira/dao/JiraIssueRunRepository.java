package com.assurant.brain.jira.dao;

import com.assurant.brain.jira.domain.JiraIssueRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JiraIssueRunRepository extends JpaRepository<JiraIssueRun, UUID> {

    Optional<JiraIssueRun> findByIssueKey(String issueKey);
}
