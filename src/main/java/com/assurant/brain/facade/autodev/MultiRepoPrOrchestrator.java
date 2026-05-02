package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PrAnnotationsBuilder;
import com.assurant.brain.codegen.SelfReviewLoop;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PrBatchRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PrBatch;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.BatchStatus;
import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.enums.PerRepoStage;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.github.PrCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Log4j2
@Service
@RequiredArgsConstructor
public class MultiRepoPrOrchestrator {

    public record RepoTarget(String projectId, String repoUrl, String baseBranch,
                              Map<String, String> files, String planSummary,
                              String jiraIssueKey) {
        public RepoTarget(String projectId, String repoUrl, String baseBranch,
                           Map<String, String> files, String planSummary) {
            this(projectId, repoUrl, baseBranch, files, planSummary, null);
        }
    }

    private final PrCreationService prCreationService;
    private final PullRequestRecordRepository prRecordRepository;
    private final PrBatchRepository prBatchRepository;
    private final SelfReviewLoop selfReviewLoop;
    private final BrainProperties brainProperties;
    private final PrAnnotationsBuilder prAnnotationsBuilder;

    @Qualifier("brainLlmExecutor")
    private final Executor brainLlmExecutor;

    public MultiRepoPrResult createBatch(UUID sessionId, List<RepoTarget> targets,
                                          String planJson) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("createBatch requires at least one RepoTarget");
        }

        PrBatch batch = new PrBatch();
        batch.setSessionId(sessionId);
        batch.setOverallStatus(BatchStatus.RUNNING);
        batch.setTotalRepos(targets.size());
        batch = prBatchRepository.save(batch);
        UUID batchId = batch.getId();

        int maxReviewIterations = brainProperties.github() != null
                ? brainProperties.github().maxSelfReviewIterations() : 3;

        List<CompletableFuture<PerRepoResult>> futures = new ArrayList<>();
        for (RepoTarget target : targets) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> processRepo(sessionId, batchId, target, planJson, maxReviewIterations),
                    brainLlmExecutor
            ).handle((result, ex) -> {
                if (ex != null) {
                    log.error("Unexpected fan-out failure for repo={}: {}", target.repoUrl(),
                            ex.getMessage(), ex);
                    return PerRepoResult.failed(target.projectId(), target.repoUrl(),
                            PerRepoStage.PR_CREATE, ex.getMessage(), null);
                }
                return result;
            }));
        }

        List<PerRepoResult> results = new ArrayList<>();
        for (CompletableFuture<PerRepoResult> f : futures) {
            results.add(f.join());
        }

        int succeeded = (int) results.stream().filter(r -> r.outcome() == PerRepoOutcome.SUCCESS).count();
        int failed    = (int) results.stream().filter(r -> r.outcome() == PerRepoOutcome.FAILED).count();
        int skipped   = (int) results.stream().filter(r -> r.outcome() == PerRepoOutcome.SKIPPED).count();

        BatchStatus overall;
        if (failed == targets.size()) overall = BatchStatus.FAILED;
        else if (failed > 0 || skipped > 0) overall = BatchStatus.PARTIAL;
        else overall = BatchStatus.COMPLETED;

        batch.setOverallStatus(overall);
        batch.setSucceeded(succeeded);
        batch.setFailed(failed);
        batch.setSkipped(skipped);
        prBatchRepository.save(batch);

        log.info("MultiRepoPrOrchestrator batch={} finished: status={}, success={}, failed={}, skipped={}",
                batchId, overall, succeeded, failed, skipped);

        return new MultiRepoPrResult(batchId, overall, targets.size(),
                succeeded, failed, skipped, results);
    }

    private PerRepoResult processRepo(UUID sessionId, UUID batchId, RepoTarget target,
                                        String planJson, int maxReviewIterations) {
        PullRequestRecord record = new PullRequestRecord();
        record.setSessionId(sessionId);
        record.setBatchId(batchId);
        record.setProjectId(target.projectId());
        record.setRepoUrl(target.repoUrl());
        record.setBaseBranch(target.baseBranch());
        record.setStatus(PrStatus.REVIEWING);
        record.setGeneratedFiles(target.files());
        record = prRecordRepository.save(record);

        Map<String, String> reviewedFiles;
        try {
            reviewedFiles = selfReviewLoop.run(record, target.files(), planJson, maxReviewIterations);
        } catch (Exception e) {
            return failRepo(record, PerRepoStage.SELF_REVIEW, e);
        }

        try {
            record.setGeneratedFiles(reviewedFiles);
            record.setStatus(PrStatus.CREATING);
            prRecordRepository.save(record);

            String prBody = prAnnotationsBuilder.renderPrBody(
                    target.projectId(), reviewedFiles, target.planSummary());
            PrCreationService.PrResult pr = prCreationService.createPullRequest(
                    target.repoUrl(), target.baseBranch(), reviewedFiles, target.planSummary(),
                    prBody, target.jiraIssueKey());

            record.setBranchName(pr.branchName());
            record.setPrNumber(pr.prNumber());
            record.setPrUrl(pr.prUrl());
            record.setStatus(PrStatus.CREATED);
            prRecordRepository.save(record);

            return PerRepoResult.success(target.projectId(), target.repoUrl(),
                    pr.prUrl(), pr.prNumber(), record.getId());
        } catch (Exception e) {
            return failRepo(record, PerRepoStage.PR_CREATE, e);
        }
    }

    private PerRepoResult failRepo(PullRequestRecord record, PerRepoStage stage, Exception e) {
        log.warn("Per-repo PR creation failed at stage={} for repo={}: {}",
                stage, record.getRepoUrl(), e.getMessage());
        record.setStatus(PrStatus.FAILED);
        record.setErrorMessage(e.getMessage());
        record.setFailureStage(stage.name());
        prRecordRepository.save(record);
        return PerRepoResult.failed(record.getProjectId(), record.getRepoUrl(),
                stage, e.getMessage(), record.getId());
    }
}
