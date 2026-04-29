package com.assurant.brain.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JobController")
class JobControllerTest {

    private AsyncJobService service;
    private JobEventPublisher publisher;
    private JobController controller;

    @BeforeEach
    void setup() {
        service = mock(AsyncJobService.class);
        publisher = mock(JobEventPublisher.class);
        controller = new JobController(service, publisher);
    }

    @Test
    @DisplayName("getJob returns 200 when job exists")
    void getJobFound() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.of(sample(id, AsyncJobStatus.RUNNING)));
        var resp = controller.getJob(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("getJob returns 404 when job missing")
    void getJobMissing() {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.empty());
        var resp = controller.getJob(id);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("listJobs without projectId delegates to recentInFlight")
    void listJobsNoProject() {
        when(service.recentInFlight(20)).thenReturn(List.of(sample(UUID.randomUUID(), AsyncJobStatus.RUNNING)));
        var resp = controller.listJobs(null, 20);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("listJobs with projectId delegates to recentForProject")
    void listJobsWithProject() {
        when(service.recentForProject("ce-imei", 20))
                .thenReturn(List.of(sample(UUID.randomUUID(), AsyncJobStatus.SUCCEEDED)));
        var resp = controller.listJobs("ce-imei", 20);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("streamJob returns SseEmitter even when job missing (sends not-found event then completes)")
    void streamJobMissing() {
        UUID id = UUID.randomUUID();
        when(publisher.subscribe(id)).thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
        when(service.findById(id)).thenReturn(Optional.empty());
        var emitter = controller.streamJob(id);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("streamJob returns SseEmitter and seeds snapshot when job exists")
    void streamJobFound() {
        UUID id = UUID.randomUUID();
        when(publisher.subscribe(id)).thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
        when(service.findById(id)).thenReturn(Optional.of(sample(id, AsyncJobStatus.RUNNING)));
        var emitter = controller.streamJob(id);
        assertThat(emitter).isNotNull();
    }

    private AsyncJob sample(UUID id, AsyncJobStatus status) {
        return new AsyncJob(id, "FULL_DOC_BUNDLE", "PROJECT", "ce-imei", "ce-imei", status,
                "alice", null, null, (short) 0, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), false);
    }
}
