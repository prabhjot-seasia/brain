package com.assurant.brain.rest.v1.ingest.controller;

import com.assurant.brain.dao.ProjectMemberRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.domain.ProjectMember;
import com.assurant.brain.dto.request.IngestRequest;
import com.assurant.brain.enums.ProjectRole;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.dto.JobStartResponse;
import com.assurant.brain.security.ProjectAccessService;
import com.assurant.brain.security.SecurityUtils;
import com.assurant.brain.service.IngestionService;
import org.springframework.http.HttpHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private final IngestionService ingestionService;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectAccessService projectAccessService;
    private final AsyncJobService asyncJobService;

    @PostMapping(value = "/projects/ingest",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(@RequestBody @Valid IngestRequest request) {
        log.info("Ingest request for project={} repo={} branch={}",
                request.projectId(), request.repoUrl(), request.effectiveBranch());

        boolean isNewProject = projectRepository.findById(request.projectId()).isEmpty();
        if (!isNewProject && !projectAccessService.canWrite(request.projectId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "no write access to project " + request.projectId()));
        }

        AsyncJob job = asyncJobService.startOrAttach(
                "INGEST_PROJECT", "PROJECT", request.projectId(), request.projectId());
        if (job.attachedToExisting()) {
            log.info("Ingest joined in-flight job={} for project={}", job.id(), request.projectId());
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                    .body(JobStartResponse.from(job));
        }

        String callerId = SecurityUtils.currentUserId();
        if (isNewProject && callerId != null && !callerId.isBlank()
                && !"anonymous".equals(callerId)
                && projectMemberRepository.findByProjectIdAndUserId(request.projectId(), callerId).isEmpty()) {
            ProjectMember owner = new ProjectMember();
            owner.setProjectId(request.projectId());
            owner.setUserId(callerId);
            owner.setRole(ProjectRole.OWNER);
            try {
                projectMemberRepository.save(owner);
                log.info("Granted OWNER role to user={} on new project={}", callerId, request.projectId());
            } catch (org.springframework.dao.DataIntegrityViolationException raceLost) {
                log.info("Concurrent ingest claimed OWNER first for project={} user={}; continuing",
                        request.projectId(), callerId);
            }
        }

        ingestionService.cloneAndIngestAsync(request, job.id());

        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }

    @GetMapping(value = "/projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Project>> listProjects() {
        if (!projectAccessService.enforceMembership()) {
            return ResponseEntity.ok(projectRepository.findAll());
        }
        String userId = SecurityUtils.currentUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return ResponseEntity.ok(List.of());
        }
        Set<String> memberOf = projectMemberRepository.findByUserId(userId).stream()
                .map(ProjectMember::getProjectId)
                .collect(java.util.stream.Collectors.toSet());
        return ResponseEntity.ok(projectRepository.findAll().stream()
                .filter(p -> memberOf.contains(p.getId()))
                .toList());
    }

    @GetMapping(value = "/projects/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<Project> getProject(@PathVariable String projectId) {
        return projectRepository.findById(projectId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/projects/{projectId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<Map<String, String>> getIngestionStatus(@PathVariable String projectId) {
        return projectRepository.findById(projectId)
                .map(p -> ResponseEntity.ok(Map.of(
                        "projectId", p.getId(),
                        "status", p.getIngestionStatus() != null ? p.getIngestionStatus().name() : "UNKNOWN",
                        "error", p.getIngestionError() != null ? p.getIngestionError() : ""
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
