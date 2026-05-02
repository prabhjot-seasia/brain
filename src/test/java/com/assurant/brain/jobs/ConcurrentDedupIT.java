package com.assurant.brain.jobs;

import com.assurant.brain.BrainApplicationTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncJob concurrent dedup (M8 — two-tab equivalent at the service layer)")
class ConcurrentDedupIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired private AsyncJobService asyncJobService;
    @Autowired private AsyncJobRepository repository;

    private static final String JOB_TYPE = "M8_DEDUP";
    private static final String TARGET_KIND = "PROJECT";
    private static final String TARGET_ID = "concurrent-target";
    private static final String PROJECT_ID = "concurrent-proj";

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID,
                org.springframework.data.domain.PageRequest.of(0, 100)));
    }

    @Test
    @DisplayName("32 concurrent startOrAttach calls produce exactly 1 row with attachedToExisting=false")
    void concurrentStartOrAttach() throws Exception {
        int parallelism = 32;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        AtomicReference<UUID> winnerJobId = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger newCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger attachedCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<UUID> seenIds = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < parallelism; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, TARGET_ID, PROJECT_ID);
                    seenIds.add(job.id());
                    if (job.attachedToExisting()) {
                        attachedCount.incrementAndGet();
                    } else {
                        newCount.incrementAndGet();
                        winnerJobId.set(job.id());
                    }
                } catch (Exception ignored) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdown();

        assertThat(errorCount.get())
                .as("No caller should see an unhandled exception after the race-recovery fix")
                .isZero();
        assertThat(newCount.get())
                .as("Exactly one caller wins the insert; all others must attach")
                .isEqualTo(1);
        assertThat(attachedCount.get())
                .as("All non-winners must report attachedToExisting=true")
                .isEqualTo(parallelism - 1);

        List<AsyncJobEntity> rows = repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID,
                org.springframework.data.domain.PageRequest.of(0, 50));
        long inFlight = rows.stream()
                .filter(r -> r.getStatus() == AsyncJobStatus.QUEUED || r.getStatus() == AsyncJobStatus.RUNNING)
                .filter(r -> JOB_TYPE.equals(r.getJobType()) && TARGET_ID.equals(r.getTargetId()))
                .count();
        assertThat(inFlight)
                .as("Partial unique index permits at most 1 in-flight row per (jobType,targetKind,targetId)")
                .isEqualTo(1);

        java.util.UUID committedId = rows.stream()
                .filter(r -> JOB_TYPE.equals(r.getJobType()) && TARGET_ID.equals(r.getTargetId())
                        && (r.getStatus() == AsyncJobStatus.QUEUED || r.getStatus() == AsyncJobStatus.RUNNING))
                .findFirst().orElseThrow().getId();
        assertThat(seenIds.stream().distinct().toList())
                .as("Every caller must have seen the same committed jobId — no ghost ids")
                .containsExactly(committedId);
        assertThat(winnerJobId.get()).isEqualTo(committedId);
    }
}
