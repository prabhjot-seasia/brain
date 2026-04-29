package com.assurant.brain.rest.v1.conventions.controller;

import com.assurant.brain.conventions.RulePack;
import com.assurant.brain.conventions.RulePackInstaller;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.jobs.dto.JobStartResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Log4j2
@RestController
@RequestMapping("/api/v1/projects/{projectId}/rule-packs")
@RequiredArgsConstructor
public class RulePackController {

    public static final String APPROVAL_HEADER = "X-Brain-Approval";

    private final RulePackInstaller installer;
    private final AsyncJobService asyncJobService;

    @Value("${brain.rulepack.approval-token:}")
    private String approvalToken;

    public record InstallRequest(@Valid @NotBlank String version,
                                  @Valid RulePack pack) {}

    @PostMapping("/install")
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canAdminister(#projectId)")
    public ResponseEntity<RulePackInstaller.InstallResult> install(
            @PathVariable String projectId,
            @RequestHeader(value = APPROVAL_HEADER, required = false) String approvalHeader,
            @Valid @RequestBody InstallRequest body) {
        boolean approved = isApproved(approvalHeader);
        if (!approved) {
            log.warn("Rule-pack install rejected for project={} pack={} — missing or invalid {} header",
                    projectId, body.pack() == null ? "?" : body.pack().id(), APPROVAL_HEADER);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "rule-pack install requires THANOS approval header " + APPROVAL_HEADER);
        }
        log.info("Rule-pack install request project={} pack={}",
                projectId, body.pack() == null ? "?" : body.pack().id());
        var result = installer.install(projectId, body.pack(), true);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/install/start")
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canAdminister(#projectId)")
    public ResponseEntity<JobStartResponse> installAsync(
            @PathVariable String projectId,
            @RequestHeader(value = APPROVAL_HEADER, required = false) String approvalHeader,
            @Valid @RequestBody InstallRequest body) {
        if (!isApproved(approvalHeader)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "rule-pack install requires THANOS approval header " + APPROVAL_HEADER);
        }
        String packId = body.pack() == null ? "_" : body.pack().id();
        String targetId = projectId + ":" + packId + ":" + body.version();
        AsyncJob job = asyncJobService.startOrAttach(
                "RULE_PACK_INSTALL", "PROJECT_PACK", targetId, projectId);
        if (!job.attachedToExisting()) {
            installer.installAsync(projectId, body.pack(), true, job.id());
        }
        return ResponseEntity.accepted()
                .header(org.springframework.http.HttpHeaders.LOCATION, "/api/v1/jobs/" + job.id())
                .body(JobStartResponse.from(job));
    }

    @DeleteMapping("/{packId}")
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canAdminister(#projectId)")
    public ResponseEntity<RulePackInstaller.UninstallResult> uninstall(
            @PathVariable String projectId,
            @PathVariable String packId,
            @RequestParam String version,
            @RequestHeader(value = APPROVAL_HEADER, required = false) String approvalHeader) {
        if (!isApproved(approvalHeader)) {
            log.warn("Rule-pack uninstall rejected for project={} pack={} — missing or invalid {} header",
                    projectId, packId, APPROVAL_HEADER);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "rule-pack uninstall requires THANOS approval header " + APPROVAL_HEADER);
        }
        var result = installer.uninstall(projectId, packId, version);
        return ResponseEntity.ok(result);
    }

    private boolean isApproved(String approvalHeader) {
        if (approvalToken == null || approvalToken.isBlank()) return false;
        return approvalToken.equals(approvalHeader);
    }
}
