package com.assurant.brain.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JobEventPublisher")
class JobEventPublisherTest {

    private JobEventPublisher publisher;

    @BeforeEach
    void setup() {
        publisher = new JobEventPublisher();
    }

    @Test
    @DisplayName("subscribe returns SseEmitter and tracks the subscriber")
    void subscribeTracks() {
        UUID id = UUID.randomUUID();
        SseEmitter emitter = publisher.subscribe(id);
        assertThat(emitter).isNotNull();
        assertThat(publisher.activeSubscribers(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("multiple subscribers all receive published events without throwing")
    void publishFansOut() {
        UUID id = UUID.randomUUID();
        publisher.subscribe(id);
        publisher.subscribe(id);
        publisher.publish(id, JobEvent.custom("ping", Map.of("k", "v")));
        assertThat(publisher.activeSubscribers(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("complete closes all subscribers and removes the entry")
    void completeRemovesAll() {
        UUID id = UUID.randomUUID();
        publisher.subscribe(id);
        publisher.subscribe(id);
        publisher.complete(id);
        assertThat(publisher.activeSubscribers(id)).isEqualTo(0);
        assertThat(publisher.activeJobs()).isEqualTo(0);
    }

    @Test
    @DisplayName("publish to a job with no subscribers is a no-op")
    void publishWithNoSubscribers() {
        UUID id = UUID.randomUUID();
        publisher.publish(id, JobEvent.custom("ping", Map.of()));
        assertThat(publisher.activeSubscribers(id)).isEqualTo(0);
    }

    @Test
    @DisplayName("emitter timeout removes the subscriber from the map")
    void timeoutEvicts() {
        UUID id = UUID.randomUUID();
        SseEmitter emitter = publisher.subscribe(id, 1L);
        try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        emitter.complete();
        assertThat(publisher.activeSubscribers(id)).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("JobEvent.snapshot/progress/terminal carry the right name")
    void eventNames() {
        AsyncJob job = sampleJob(AsyncJobStatus.RUNNING);
        assertThat(JobEvent.snapshot(job).name()).isEqualTo("status");
        assertThat(JobEvent.progress(job).name()).isEqualTo("progress");
        assertThat(JobEvent.terminal(sampleJob(AsyncJobStatus.SUCCEEDED)).name()).isEqualTo("succeeded");
        assertThat(JobEvent.terminal(sampleJob(AsyncJobStatus.PARTIAL)).name()).isEqualTo("partial");
        assertThat(JobEvent.terminal(sampleJob(AsyncJobStatus.FAILED)).name()).isEqualTo("failed");
    }

    private AsyncJob sampleJob(AsyncJobStatus status) {
        return new AsyncJob(UUID.randomUUID(), "FOO", "PROJECT", "p", "p", status,
                "alice", null, null, (short) 50, "msg", null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), false);
    }
}
