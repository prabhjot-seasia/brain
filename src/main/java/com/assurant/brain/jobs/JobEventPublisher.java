package com.assurant.brain.jobs;

import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Log4j2
@Component
public class JobEventPublisher {

    private static final long DEFAULT_TIMEOUT_MS = 600_000L;

    private final ConcurrentMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId) {
        return subscribe(jobId, DEFAULT_TIMEOUT_MS);
    }

    public SseEmitter subscribe(UUID jobId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> { remove(jobId, emitter); emitter.complete(); });
        emitter.onError(t -> remove(jobId, emitter));
        return emitter;
    }

    public void publish(UUID jobId, JobEvent event) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping failed SSE emitter for job={}: {}", jobId, e.getMessage());
                remove(jobId, emitter);
            }
        }
    }

    public void complete(UUID jobId) {
        List<SseEmitter> list = emitters.remove(jobId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // emitter already closed by client disconnect
            }
        }
    }

    public int activeSubscribers(UUID jobId) {
        List<SseEmitter> list = emitters.get(jobId);
        return list == null ? 0 : list.size();
    }

    public int activeJobs() {
        return emitters.size();
    }

    @Scheduled(fixedDelayString = "${brain.jobs.sse-heartbeat-ms:15000}")
    public void sendHeartbeats() {
        if (emitters.isEmpty()) return;
        for (Map.Entry<UUID, List<SseEmitter>> entry : emitters.entrySet()) {
            UUID jobId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Heartbeat dropped emitter for job={}: {}", jobId, e.getMessage());
                    remove(jobId, emitter);
                }
            }
        }
    }

    private void remove(UUID jobId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) emitters.remove(jobId, list);
    }
}
