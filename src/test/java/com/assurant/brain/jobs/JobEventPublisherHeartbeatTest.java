package com.assurant.brain.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JobEventPublisher heartbeat — H6")
class JobEventPublisherHeartbeatTest {

    @Test
    @DisplayName("sendHeartbeats emits an SSE comment to every active emitter")
    void emitsCommentToAllSubscribers() throws IOException {
        JobEventPublisher publisher = new JobEventPublisher();
        UUID jobId = UUID.randomUUID();
        AtomicInteger sendCalls = new AtomicInteger();
        List<SseEmitter.SseEventBuilder> captured = new ArrayList<>();

        SseEmitter emitter = new SseEmitter() {
            @Override public void send(SseEventBuilder builder) {
                sendCalls.incrementAndGet();
                captured.add(builder);
            }
        };
        publisher.subscribe(jobId, 60_000L);

        @SuppressWarnings("unchecked")
        var emitters = (java.util.concurrent.ConcurrentMap<UUID, java.util.List<SseEmitter>>)
                org.springframework.test.util.ReflectionTestUtils.getField(publisher, "emitters");
        emitters.get(jobId).clear();
        emitters.get(jobId).add(emitter);

        publisher.sendHeartbeats();

        assertThat(sendCalls.get()).isEqualTo(1);
        assertThat(captured).hasSize(1);
    }

    @Test
    @DisplayName("sendHeartbeats with no subscribers is a no-op")
    void noSubscribersNoOp() {
        JobEventPublisher publisher = new JobEventPublisher();
        publisher.sendHeartbeats();
        assertThat(publisher.activeJobs()).isZero();
    }
}
