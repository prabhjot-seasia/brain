package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.dao.ProjectMemberRepository;
import com.assurant.brain.domain.ProjectMember;
import com.assurant.brain.enums.ProjectRole;
import com.assurant.brain.jobs.AsyncJobService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Log4j2
@Component("projectAccess")
public class ProjectAccessService {

    private final ProjectMemberRepository memberRepository;
    private final BrainProperties brainProperties;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final ObjectProvider<AsyncJobService> asyncJobServiceProvider;

    public ProjectAccessService(ProjectMemberRepository memberRepository,
                                BrainProperties brainProperties,
                                GeneratedDocumentRepository generatedDocumentRepository,
                                ObjectProvider<AsyncJobService> asyncJobServiceProvider) {
        this.memberRepository = memberRepository;
        this.brainProperties = brainProperties;
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.asyncJobServiceProvider = asyncJobServiceProvider;
    }

    public boolean canReadDocument(UUID documentId) {
        if (documentId == null) return true;
        return generatedDocumentRepository.findById(documentId)
                .map(d -> canRead(d.getProjectId()))
                .orElse(true);
    }

    public boolean canWriteDocument(UUID documentId) {
        if (documentId == null) return true;
        return generatedDocumentRepository.findById(documentId)
                .map(d -> canWrite(d.getProjectId()))
                .orElse(true);
    }

    public boolean canReadJob(UUID jobId) {
        if (jobId == null) return true;
        AsyncJobService svc = asyncJobServiceProvider.getIfAvailable();
        if (svc == null) return true;
        return svc.projectIdOf(jobId).map(this::canRead).orElse(true);
    }

    public boolean enforceMembership() {
        return brainProperties.security() != null
                && brainProperties.security().enforceProjectMembership();
    }

    public boolean canRead(String projectId) {
        return checkAccess(projectId, ProjectRole::canRead, "READ");
    }

    public boolean canWrite(String projectId) {
        return checkAccess(projectId, ProjectRole::canWrite, "WRITE");
    }

    public boolean canAdminister(String projectId) {
        return checkAccess(projectId, ProjectRole::canAdminister, "ADMIN");
    }

    private boolean checkAccess(String projectId, java.util.function.Predicate<ProjectRole> predicate,
                                 String op) {
        if (projectId == null || projectId.isBlank()) {
            return true;
        }
        String userId = SecurityUtils.currentUserId();
        boolean enforce = brainProperties.security() != null
                && brainProperties.security().enforceProjectMembership();

        if (userId == null || userId.isBlank()) {
            if (enforce) {
                log.warn("Project-membership: denied {} on project={} — no authenticated user", op, projectId);
                return false;
            }
            log.debug("Project-membership soft-mode: anonymous allowed {} on project={}", op, projectId);
            return true;
        }

        Optional<ProjectMember> member = memberRepository.findByProjectIdAndUserId(projectId, userId);
        boolean allowed = member.isPresent() && predicate.test(member.get().getRole());

        if (allowed) return true;

        if (enforce) {
            log.warn("Project-membership: denied {} for user={} on project={} (member={})",
                    op, userId, projectId, member.map(m -> m.getRole().name()).orElse("none"));
            return false;
        }
        log.warn("Project-membership soft-mode: would-deny {} for user={} on project={} (member={}) — allowing",
                op, userId, projectId, member.map(m -> m.getRole().name()).orElse("none"));
        return true;
    }
}
