package com.assurant.brain.jobs;

import com.assurant.brain.BrainApplicationTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AsyncJob in-flight dedup uses Index Scan (M2 — partial unique index plan)")
class AsyncJobIndexPlanIT extends BrainApplicationTests {

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
    @Autowired private JdbcTemplate jdbc;

    private static final int SEED_ROWS = 500;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("plan-proj",
                org.springframework.data.domain.PageRequest.of(0, SEED_ROWS + 100)));
    }

    @Test
    @DisplayName("uq_async_jobs_inflight partial unique index exists with correct WHERE clause")
    void partialIndexExists() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'async_jobs' AND indexname = 'uq_async_jobs_inflight'");
        assertThat(rows).as("partial unique index uq_async_jobs_inflight must exist").hasSize(1);
        String def = String.valueOf(rows.get(0).get("indexdef")).toLowerCase();
        assertThat(def).contains("unique");
        assertThat(def).contains("queued").contains("running");
    }

    @Test
    @DisplayName("dedup lookup uses Index Scan, not Seq Scan, with several hundred rows present")
    void dedupLookupUsesIndex() {
        for (int i = 0; i < SEED_ROWS; i++) {
            AsyncJob job = asyncJobService.startOrAttach(
                    "PLAN_TEST", "PROJECT", "plan-target-" + i, "plan-proj");
            asyncJobService.markSucceeded(job.id(), Map.of("i", i));
        }
        AsyncJob inFlight = asyncJobService.startOrAttach(
                "PLAN_TEST", "PROJECT", "plan-target-active", "plan-proj");
        assertThat(inFlight).isNotNull();

        jdbc.execute("ANALYZE async_jobs");

        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN (FORMAT TEXT) " +
                "SELECT id FROM async_jobs " +
                "WHERE job_type = 'PLAN_TEST' AND target_kind = 'PROJECT' " +
                "AND target_id = 'plan-target-active' AND status IN ('QUEUED','RUNNING')");

        StringBuilder planText = new StringBuilder();
        for (Map<String, Object> row : plan) planText.append(row.values().iterator().next()).append('\n');
        String planLower = planText.toString().toLowerCase();

        assertThat(planLower)
                .as("Dedup lookup should use Index Scan over uq_async_jobs_inflight, not Seq Scan. Plan:\n" + planText)
                .contains("index");
        assertThat(planLower)
                .as("Plan should NOT do a sequential scan on async_jobs at this scale")
                .doesNotContain("seq scan on async_jobs");
    }
}
