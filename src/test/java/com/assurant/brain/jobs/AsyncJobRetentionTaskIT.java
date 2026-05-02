package com.assurant.brain.jobs;

import com.assurant.brain.BrainApplicationTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncJobRetentionTask — H5")
class AsyncJobRetentionTaskIT extends BrainApplicationTests {

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
    @Autowired private AsyncJobRetentionTask retentionTask;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("h5",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("purgeExpired deletes terminal rows older than retention-days; leaves in-flight rows untouched")
    void purgeKeepsInFlight() {
        AsyncJob inFlight = asyncJobService.startOrAttach("RET_KEEP", "PROJECT", "h5-running", "h5");

        AsyncJob old = asyncJobService.startOrAttach("RET_OLD", "PROJECT", "h5-old", "h5");
        asyncJobService.markSucceeded(old.id(), java.util.Map.of());
        backdate(old.id(), 100);

        AsyncJob recent = asyncJobService.startOrAttach("RET_RECENT", "PROJECT", "h5-recent", "h5");
        asyncJobService.markSucceeded(recent.id(), java.util.Map.of());

        ReflectionTestUtils.setField(retentionTask, "retentionDays", 90);
        retentionTask.purgeExpired();

        assertThat(repository.findById(old.id())).isEmpty();
        assertThat(repository.findById(recent.id())).isPresent();
        assertThat(repository.findById(inFlight.id())).isPresent();
    }

    @Test
    @DisplayName("retentionDays<=0 short-circuits — nothing is deleted")
    void retentionDisabledIsNoOp() {
        AsyncJob old = asyncJobService.startOrAttach("RET_DISABLED", "PROJECT", "h5-disabled", "h5");
        asyncJobService.markSucceeded(old.id(), java.util.Map.of());
        backdate(old.id(), 1000);

        ReflectionTestUtils.setField(retentionTask, "retentionDays", 0);
        retentionTask.purgeExpired();

        assertThat(repository.findById(old.id())).isPresent();
    }

    private void backdate(UUID id, int days) {
        OffsetDateTime backdated = OffsetDateTime.now().minusDays(days);
        jdbc.update("UPDATE async_jobs SET finished_at = ?, updated_at = ? WHERE id = ?",
                backdated, backdated, id);
    }
}
