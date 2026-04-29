package com.assurant.brain.dao;

import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClarificationSessionRepository extends JpaRepository<ClarificationSession, UUID> {

    List<ClarificationSession> findByProjectIdAndStatus(String projectId, SessionStatus status);
}
