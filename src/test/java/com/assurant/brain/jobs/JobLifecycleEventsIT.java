package com.assurant.brain.jobs;

import com.assurant.brain.BrainApplicationTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("JobEventPublisher — terminal events (H7, H8)")
class JobLifecycleEventsIT extends BrainApplicationTests {

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
    @Autowired private JobEventPublisher publisher;

    @AfterEach
    void cleanup() {
        repository.deleteAll(repository.findByProjectIdOrderByCreatedAtDesc("h7-h8",
                org.springframework.data.domain.PageRequest.of(0, 50)));
    }

    @Test
    @DisplayName("H7 — markFailed publishes event:failed and closes the SSE emitter")
    void markFailedClosesEmitter() throws Exception {
        AsyncJob job = asyncJobService.startOrAttach("LIFECYCLE_FAIL", "PROJECT", "h7-target", "h7-h8");

        MvcResult result = mvc.perform(get("/api/v1/jobs/stream/{id}", job.id())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andReturn();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            asyncJobService.markFailed(job.id(), "boom");
        }).start();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:failed");
        assertThat(body).contains("\"status\":\"FAILED\"");
        assertThat(publisher.activeSubscribers(job.id())).isZero();
    }

    @Test
    @DisplayName("H8 — markPartial publishes event:partial with payload, closes emitter")
    void markPartialClosesEmitter() throws Exception {
        AsyncJob job = asyncJobService.startOrAttach("LIFECYCLE_PARTIAL", "PROJECT", "h8-target", "h7-h8");

        MvcResult result = mvc.perform(get("/api/v1/jobs/stream/{id}", job.id())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andReturn();
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            asyncJobService.markPartial(job.id(), Map.of("ok", 3, "failed", 1));
        }).start();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:partial");
        assertThat(body).contains("\"status\":\"PARTIAL\"");
        assertThat(publisher.activeSubscribers(job.id())).isZero();
    }
}
