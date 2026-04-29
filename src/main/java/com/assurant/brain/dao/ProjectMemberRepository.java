package com.assurant.brain.dao;

import com.assurant.brain.domain.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    Optional<ProjectMember> findByProjectIdAndUserId(String projectId, String userId);

    List<ProjectMember> findByUserId(String userId);

    List<ProjectMember> findByProjectId(String projectId);

    long countByProjectId(String projectId);
}
