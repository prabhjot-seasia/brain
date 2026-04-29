package com.assurant.brain.docs;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Log4j2
@Component
public class FullDocRateLimiter {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(15);
    private static final int BURST = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration EVICTION_HORIZON = Duration.ofHours(1);
    private static final int EVICT_EVERY_N_CHECKS = 100;

    private final ConcurrentMap<String, KeyState> state = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger checksSinceEvict = new java.util.concurrent.atomic.AtomicInteger(0);

    public void check(String key) {
        if (checksSinceEvict.incrementAndGet() >= EVICT_EVERY_N_CHECKS) {
            evictStale();
            checksSinceEvict.set(0);
        }
        Instant now = Instant.now();
        KeyState s = state.compute(key, (k, prev) -> {
            if (prev == null) return new KeyState(now, 1);
            if (now.isAfter(prev.windowStart.plus(WINDOW))) {
                return new KeyState(now, 1);
            }
            return new KeyState(prev.windowStart, prev.count + 1);
        });
        if (s.count > BURST) {
            throw new RateLimitExceededException(
                    "Too many full-doc requests for " + key + " — limit "
                            + BURST + " per " + WINDOW.toMinutes() + " min");
        }
        Instant lastSeen = state.getOrDefault(key + ":last", new KeyState(Instant.EPOCH, 0)).windowStart;
        if (Duration.between(lastSeen, now).compareTo(MIN_INTERVAL) < 0) {
            throw new RateLimitExceededException(
                    "Full-doc requests for " + key + " must be at least "
                            + MIN_INTERVAL.toSeconds() + " s apart");
        }
        state.put(key + ":last", new KeyState(now, 0));
    }

    private void evictStale() {
        Instant cutoff = Instant.now().minus(EVICTION_HORIZON);
        int before = state.size();
        state.entrySet().removeIf(e -> e.getValue().windowStart.isBefore(cutoff));
        int evicted = before - state.size();
        if (evicted > 0) log.debug("FullDocRateLimiter evicted {} stale keys; remaining={}", evicted, state.size());
    }

    private record KeyState(Instant windowStart, int count) {}

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }
}
