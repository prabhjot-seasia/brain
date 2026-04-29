package com.assurant.brain.jobs;

import com.assurant.brain.BrainApplicationTests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deterministic JUnit IT covering the async-job system end-to-end at the API +
 * service layer. These tests don't depend on real network timing (clone, LLM,
 * etc.); they drive AsyncJobService directly + assert via MockMvc on the REST
 * surface. They are the deterministic counterpart to the @ui BDD smokes.
 *
 * Covers CRITICAL items C8–C13 from the BDD coverage plan.
 */
@DisplayName("JobController + AsyncJobService — async-job lifecycle IT")
class JobControllerLifecycleIT extends BrainApplicationTests {

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
    @Autowired private ObjectMapper objectMapper;

    private static final String JOB_TYPE = "TEST_LIFECYCLE";
    private static final String TARGET_KIND = "PROJECT";

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("it-target",
                org.springframework.data.domain.PageRequest.of(0, 100)));
    }

    // ── C8 — /jobs/{id} polling: snapshot reflects current row state ─
    @Nested
    @DisplayName("C9 — GET /api/v1/jobs/{id} polling fallback")
    class PollingFallback {
        @Test
        @DisplayName("returns the current snapshot for a known job, including projectId + status")
        void returnsSnapshot() throws Exception {
            AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");
            asyncJobService.markRunning(job.id(), "Running it-target");

            mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(job.id().toString()))
                    .andExpect(jsonPath("$.jobType").value(JOB_TYPE))
                    .andExpect(jsonPath("$.targetId").value("it-target"))
                    .andExpect(jsonPath("$.projectId").value("it-target"))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.progressMsg").value("Running it-target"));
        }

        @Test
        @DisplayName("returns 404 for an unknown job id")
        void unknownIdReturns404() throws Exception {
            mvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
    }

    // ── C10 — /jobs?projectId= listing ──────────────────────────────
    @Nested
    @DisplayName("C10 — GET /api/v1/jobs listing with filters")
    class Listing {
        @Test
        @DisplayName("filters by projectId and orders by createdAt desc")
        void filtersByProject() throws Exception {
            AsyncJob a = asyncJobService.startOrAttach("FOO", TARGET_KIND, "p1-a", "it-target");
            asyncJobService.markSucceeded(a.id(), Map.of("ok", true));
            AsyncJob b = asyncJobService.startOrAttach("BAR", TARGET_KIND, "p1-b", "it-target");
            asyncJobService.markFailed(b.id(), "boom");
            // unrelated row that must NOT appear in projectId=it-target listing
            AsyncJob c = asyncJobService.startOrAttach("BAZ", TARGET_KIND, "p2-c", "other");
            asyncJobService.markSucceeded(c.id(), Map.of());

            mvc.perform(get("/api/v1/jobs").param("projectId", "it-target").param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[*].targetId").value(org.hamcrest.Matchers.everyItem(
                            org.hamcrest.Matchers.startsWith("p1-"))));

            // Cleanup the cross-project row so @AfterEach doesn't leave it
            repository.deleteById(c.id());
        }
    }

    // ── C11 — Dedup contract ─────────────────────────────────────────
    @Nested
    @DisplayName("C11 — startOrAttach dedup contract")
    class Dedup {
        @Test
        @DisplayName("two startOrAttach calls for same triple while in-flight return SAME jobId; second has attachedToExisting=true")
        void dedups() {
            AsyncJob first = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");
            AsyncJob second = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");

            assertThat(first.attachedToExisting()).isFalse();
            assertThat(second.attachedToExisting()).isTrue();
            assertThat(second.id()).isEqualTo(first.id());

            long inFlight = repository.findByProjectIdOrderByCreatedAtDesc("it-target",
                    org.springframework.data.domain.PageRequest.of(0, 10)).stream()
                    .filter(e -> e.getStatus() == AsyncJobStatus.QUEUED || e.getStatus() == AsyncJobStatus.RUNNING)
                    .count();
            assertThat(inFlight).as("only one row may be in-flight per (jobType, targetKind, targetId)").isEqualTo(1);
        }

        @Test
        @DisplayName("partial unique index uq_async_jobs_inflight blocks a manual duplicate insert")
        void indexBlocksManualDuplicate() {
            AsyncJob first = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");

            AsyncJobEntity dup = new AsyncJobEntity();
            dup.setJobType(JOB_TYPE);
            dup.setTargetKind(TARGET_KIND);
            dup.setTargetId("it-target");
            dup.setProjectId("it-target");
            dup.setStatus(AsyncJobStatus.QUEUED);
            assertThatThrownBy(() -> repository.saveAndFlush(dup))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(first.id()).isNotNull();
        }
    }

    // ── C12 — Race-recovery: peer terminated between insert-fail and lookup ─
    @Nested
    @DisplayName("C12 — race-recovery when peer terminates")
    class RaceRecovery {
        @Test
        @DisplayName("after first job terminates, subsequent startOrAttach inserts a fresh row")
        void afterTerminalNewRowInserted() {
            AsyncJob first = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");
            asyncJobService.markFailed(first.id(), "first failed");

            AsyncJob second = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");

            assertThat(second.attachedToExisting()).isFalse();
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(second.status()).isEqualTo(AsyncJobStatus.QUEUED);
        }
    }

    // ── C8 — /jobs/stream/{id} SSE produces snapshot + terminal events ─
    @Nested
    @DisplayName("C8 — GET /api/v1/jobs/stream/{id}")
    class SseStream {
        @Test
        @DisplayName("subscribe + emit progress + terminal: stream payload contains all three event names")
        void streamCarriesLifecycle() throws Exception {
            AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");

            // Async-dispatch the GET so we can publish events while it's open.
            MvcResult result = mvc.perform(get("/api/v1/jobs/stream/{id}", job.id())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andReturn();
            // Trigger lifecycle on a separate thread so the stream sees them.
            new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                asyncJobService.markRunning(job.id(), "Running it-target");
                asyncJobService.updateProgress(job.id(), 50, "Halfway");
                asyncJobService.markSucceeded(job.id(), Map.of("ok", true));
            }).start();

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .asyncDispatch(result))
                    .andExpect(status().isOk());

            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("event:status").contains("event:progress").contains("event:succeeded");
            assertThat(body).contains("\"id\":\"" + job.id() + "\"");
            assertThat(body).contains("\"status\":\"SUCCEEDED\"");
        }

        @Test
        @DisplayName("snapshot event includes the full AsyncJob row at subscribe time")
        void snapshotCarriesRow() throws Exception {
            AsyncJob job = asyncJobService.startOrAttach(JOB_TYPE, TARGET_KIND, "it-target", "it-target");
            asyncJobService.markRunning(job.id(), "Running it-target");

            MvcResult result = mvc.perform(get("/api/v1/jobs/stream/{id}", job.id())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andReturn();
            new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                asyncJobService.markSucceeded(job.id(), Map.of());
            }).start();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .asyncDispatch(result))
                    .andExpect(status().isOk());

            String body = result.getResponse().getContentAsString();
            JsonNode snapshot = extractEventPayload(body, "status");
            assertThat(snapshot.get("job").get("status").asText()).isEqualTo("RUNNING");
            assertThat(snapshot.get("job").get("progressMsg").asText()).isEqualTo("Running it-target");
        }

        private JsonNode extractEventPayload(String body, String eventName) throws Exception {
            String marker = "event:" + eventName + "\n";
            int idx = body.indexOf(marker);
            assertThat(idx).as("event:%s in stream body", eventName).isGreaterThanOrEqualTo(0);
            int dataIdx = body.indexOf("data:", idx) + "data:".length();
            int end = body.indexOf("\n\n", dataIdx);
            String json = body.substring(dataIdx, end >= 0 ? end : body.length()).trim();
            return objectMapper.readTree(json);
        }
    }
}
