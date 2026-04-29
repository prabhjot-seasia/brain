package com.assurant.brain.ci;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.CiRemediationAttemptRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CiRemediationAttempt;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.RemediationStatus;
import com.assurant.brain.github.GitHubClient;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.util.GitHubUrlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class CiRemediationService {

    private final CiFailureParser ciFailureParser;
    private final CodeGeneratorService codeGeneratorService;
    private final GitHubClient gitHubClient;
    private final PullRequestRecordRepository prRecordRepository;
    private final CiRemediationAttemptRepository remediationRepository;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final AsyncJobService asyncJobService;

    @Async("brainLlmExecutor")
    public void remediateAsync(UUID prRecordId, long workflowRunId, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Analyzing CI failure for PR " + prRecordId);
            CiRemediationAttempt attempt = remediate(prRecordId, workflowRunId, jobId);
            switch (attempt.getStatus()) {
                case PUSHED, RESOLVED -> asyncJobService.markSucceeded(jobId, Map.of(
                        "attemptId", attempt.getId().toString(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "status", attempt.getStatus().name()));
                case EXHAUSTED -> asyncJobService.markPartial(jobId, Map.of(
                        "attemptId", attempt.getId().toString(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "status", attempt.getStatus().name(),
                        "failures", attempt.getFailureSummary() == null ? List.of() : attempt.getFailureSummary()));
                default -> asyncJobService.markSucceeded(jobId, Map.of(
                        "attemptId", attempt.getId().toString(),
                        "status", attempt.getStatus().name()));
            }
        } catch (Exception e) {
            log.error("CI remediation job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Transactional
    public CiRemediationAttempt remediate(UUID prRecordId, long workflowRunId) {
        return remediate(prRecordId, workflowRunId, null);
    }

    @Transactional
    public CiRemediationAttempt remediate(UUID prRecordId, long workflowRunId, UUID jobId) {
        PullRequestRecord prRecord = prRecordRepository.findById(prRecordId)
                .orElseThrow(() -> new IllegalArgumentException("PR record not found: " + prRecordId));

        int existingAttempts = remediationRepository.countByPrRecordId(prRecordId);
        int maxAttempts = brainProperties.ci().maxRemediationAttempts();

        if (existingAttempts >= maxAttempts) {
            log.warn("Max remediation attempts ({}) reached for PR record={}", maxAttempts, prRecordId);
            CiRemediationAttempt exhausted = createAttempt(prRecordId, workflowRunId, existingAttempts + 1);
            exhausted.setStatus(RemediationStatus.EXHAUSTED);
            exhausted.setFailureSummary(List.of("Maximum remediation attempts (" + maxAttempts + ") reached"));
            return remediationRepository.save(exhausted);
        }

        CiRemediationAttempt attempt = createAttempt(prRecordId, workflowRunId, existingAttempts + 1);
        attempt.setStatus(RemediationStatus.ANALYZING);
        attempt = remediationRepository.save(attempt);
        if (jobId != null) asyncJobService.updateProgress(jobId, 20,
                "Parsing CI logs (attempt " + attempt.getAttemptNumber() + ")");

        try {
            GitHubUrlParser.OwnerRepo ownerRepo = GitHubUrlParser.parse(prRecord.getRepoUrl());
            String logs = gitHubClient.getWorkflowRunLogs(
                    ownerRepo.owner(), ownerRepo.repo(), workflowRunId);

            CiFailureParser.FailureAnalysis analysis = ciFailureParser.parse(logs);
            attempt.setFailureSummary(analysis.failures());

            if (analysis.failures().isEmpty()) {
                log.info("No actionable failures found in CI logs for PR record={}", prRecordId);
                attempt.setStatus(RemediationStatus.RESOLVED);
                return remediationRepository.save(attempt);
            }

            attempt.setStatus(RemediationStatus.FIXING);
            remediationRepository.save(attempt);
            if (jobId != null) asyncJobService.updateProgress(jobId, 60,
                    "Generating fix for " + analysis.failures().size() + " failure(s)");

            Map<String, String> currentFiles = prRecord.getGeneratedFiles();
            if (currentFiles == null || currentFiles.isEmpty()) {
                throw new IllegalStateException("PR record has no generated files to fix.");
            }

            String planJson = "{}";
            if (prRecord.getSessionId() != null) {
                planJson = objectMapper.writeValueAsString(Map.of("ciFailures", analysis.failures()));
            }

            Map<String, String> fixedFiles = codeGeneratorService.fixCode(
                    currentFiles, analysis.failures(), planJson);

            for (Map.Entry<String, String> entry : fixedFiles.entrySet()) {
                if (!entry.getValue().equals(currentFiles.get(entry.getKey()))) {
                    gitHubClient.createOrUpdateFile(
                            ownerRepo.owner(), ownerRepo.repo(), prRecord.getBranchName(),
                            entry.getKey(), entry.getValue(),
                            "brain: fix CI failure (attempt " + attempt.getAttemptNumber() + ")"
                    );
                }
            }

            prRecord.setGeneratedFiles(fixedFiles);
            prRecordRepository.save(prRecord);

            attempt.setFixesApplied(analysis.failures());
            attempt.setStatus(RemediationStatus.PUSHED);
            return remediationRepository.save(attempt);

        } catch (Exception e) {
            log.error("CI remediation failed for PR record={}: {}", prRecordId, e.getMessage(), e);
            attempt.setStatus(RemediationStatus.EXHAUSTED);
            attempt.setFailureSummary(List.of("Remediation failed: " + e.getMessage()));
            return remediationRepository.save(attempt);
        }
    }

    private CiRemediationAttempt createAttempt(UUID prRecordId, long workflowRunId, int attemptNumber) {
        CiRemediationAttempt attempt = new CiRemediationAttempt();
        attempt.setPrRecordId(prRecordId);
        attempt.setWorkflowRunId(workflowRunId);
        attempt.setAttemptNumber(attemptNumber);
        return attempt;
    }

}
