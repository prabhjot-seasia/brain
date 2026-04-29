package com.assurant.brain.avenger;

import com.assurant.brain.avenger.dto.AvengerRequest;
import com.assurant.brain.avenger.dto.AvengerResponse;
import com.assurant.brain.avenger.dto.FullReviewResponse;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.assurant.brain.jobs.AsyncJobService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
public class AvengerOrchestrator {

    private final AvengerReviewer reviewer;
    private final Executor executor;
    private final AsyncJobService asyncJobService;

    public AvengerOrchestrator(AvengerReviewer reviewer,
                               @Qualifier("brainLlmExecutor") Executor executor,
                               AsyncJobService asyncJobService) {
        this.reviewer = reviewer;
        this.executor = executor;
        this.asyncJobService = asyncJobService;
    }

    public FullReviewResponse runFullReview(String projectId, String code, String context) {
        return runFullReviewInternal(projectId, code, context, null);
    }

    @Async("brainLlmExecutor")
    public void runFullReviewAsync(String projectId, String code, String context, UUID jobId) {
        try {
            FullReviewResponse response = runFullReviewInternal(projectId, code, context, jobId);
            asyncJobService.markSucceeded(jobId, response);
        } catch (Exception e) {
            log.error("Avenger full-review job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private FullReviewResponse runFullReviewInternal(String projectId, String code, String context, UUID jobId) {
        log.info("Running full Avengers review for project={} jobId={}", projectId, jobId);
        long start = System.currentTimeMillis();
        AvengerType[] types = AvengerType.values();
        int total = types.length;
        if (jobId != null) {
            asyncJobService.markRunning(jobId, "Reviewing across " + total + " avengers");
        }

        AtomicInteger completed = new AtomicInteger(0);
        List<CompletableFuture<AvengerResponse>> futures = Arrays.stream(types)
                .map(type -> CompletableFuture.supplyAsync(
                                () -> reviewer.review(new AvengerRequest(type, projectId, code, context)),
                                executor)
                        .whenComplete((resp, err) -> {
                            int done = completed.incrementAndGet();
                            if (jobId != null) {
                                int pct = (int) Math.floor(100.0 * done / total);
                                asyncJobService.updateProgress(jobId, pct,
                                        type.name() + " " + (err == null ? "ok" : "failed") + " (" + done + "/" + total + ")");
                            }
                        }))
                .toList();

        List<AvengerResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        long totalLatency = System.currentTimeMillis() - start;

        int approved = countByVerdict(responses, AvengerVerdict.APPROVED);
        int changesRequested = countByVerdict(responses, AvengerVerdict.CHANGES_REQUESTED);
        int blocked = countByVerdict(responses, AvengerVerdict.BLOCKED);

        AvengerVerdict overall = blocked > 0 ? AvengerVerdict.BLOCKED
                : changesRequested > 0 ? AvengerVerdict.CHANGES_REQUESTED
                : AvengerVerdict.APPROVED;

        return new FullReviewResponse(overall, responses.size(), approved, changesRequested, blocked,
                totalLatency, responses);
    }

    private int countByVerdict(List<AvengerResponse> responses, AvengerVerdict verdict) {
        return (int) responses.stream().filter(r -> r.verdict() == verdict).count();
    }
}
