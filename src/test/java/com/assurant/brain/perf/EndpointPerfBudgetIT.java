package com.assurant.brain.perf;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.jobs.AsyncJob;
import com.assurant.brain.jobs.AsyncJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@DisplayName("HULK — endpoint response-time budgets (M3)")
class EndpointPerfBudgetIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired private AsyncJobService asyncJobService;
    @Autowired private com.assurant.brain.jobs.AsyncJobRepository jobRepository;

    private static final long HEALTH_BUDGET_MS = 200L;
    private static final long CRUD_BUDGET_MS = 500L;

    @AfterEach
    void cleanup() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /actuator/health returns within HEALTH_BUDGET_MS (warm cache)")
    void healthBudget() throws Exception {
        // Warm-up: first hit pays one-time HikariCP/Redis lazy-init cost
        mvc.perform(get("/actuator/health"));

        long start = System.currentTimeMillis();
        mvc.perform(get("/actuator/health"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("/actuator/health budget = " + HEALTH_BUDGET_MS + "ms (HULK rule). Actual: " + elapsed + "ms")
                .isLessThanOrEqualTo(HEALTH_BUDGET_MS);
    }

    @Test
    @DisplayName("GET /api/v1/jobs/{id} returns within CRUD_BUDGET_MS (warm cache)")
    void jobLookupBudget() throws Exception {
        AsyncJob job = asyncJobService.startOrAttach("PERF_TEST", "PROJECT", "perf-tgt", "perf-proj");
        UUID id = job.id();

        // Warm-up
        mvc.perform(get("/api/v1/jobs/{id}", id));

        long start = System.currentTimeMillis();
        mvc.perform(get("/api/v1/jobs/{id}", id));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("/jobs/{id} budget = " + CRUD_BUDGET_MS + "ms. Actual: " + elapsed + "ms")
                .isLessThanOrEqualTo(CRUD_BUDGET_MS);
    }

    @Test
    @DisplayName("GET /api/v1/jobs?projectId= listing returns within CRUD_BUDGET_MS")
    void jobListingBudget() throws Exception {
        for (int i = 0; i < 10; i++) {
            AsyncJob job = asyncJobService.startOrAttach("PERF_LIST_" + i, "PROJECT", "list-" + i, "perf-proj");
            asyncJobService.markSucceeded(job.id(), java.util.Map.of("i", i));
        }

        // Warm-up
        mvc.perform(get("/api/v1/jobs").param("projectId", "perf-proj").param("limit", "20"));

        long start = System.currentTimeMillis();
        mvc.perform(get("/api/v1/jobs").param("projectId", "perf-proj").param("limit", "20"));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("/jobs?projectId= listing budget = " + CRUD_BUDGET_MS + "ms. Actual: " + elapsed + "ms")
                .isLessThanOrEqualTo(CRUD_BUDGET_MS);
    }
}
