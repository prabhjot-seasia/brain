package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.observability.BrainMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class AvengerMemory {

    private int maxEventsToScan()  { return brainProperties.learning() != null ? brainProperties.learning().maxEventsToScan()    : 200; }
    private int topViolations()    { return brainProperties.learning() != null ? brainProperties.learning().topViolationsCount() : 5; }
    private int recentIssues()     { return brainProperties.learning() != null ? brainProperties.learning().recentIssuesCount()  : 10; }

    private final LearningEventRepository learningEventRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConventionKeyExtractor conventionKeyExtractor;
    private final BrainProperties brainProperties;
    private final BrainMetrics brainMetrics;

    public AvengerMemorySnapshot getMemory(AvengerType avenger, String projectId) {
        String key = cacheKey(avenger, projectId);
        String cached = safeGet(key);
        if (cached != null) {
            try {
                AvengerMemorySnapshot snapshot = objectMapper.readValue(cached, AvengerMemorySnapshot.class);
                brainMetrics.recordAvengerMemoryHit(avenger);
                return snapshot;
            } catch (Exception e) {
                log.warn("Failed to deserialize AvengerMemory cache for {}:{} — rebuilding: {}",
                        avenger, projectId, e.getMessage());
            }
        }

        brainMetrics.recordAvengerMemoryMiss(avenger);
        AvengerMemorySnapshot snapshot = rebuild(avenger, projectId);
        cache(key, snapshot);
        return snapshot;
    }

    public void invalidate(AvengerType avenger, String projectId) {
        try {
            redisTemplate.delete(cacheKey(avenger, projectId));
        } catch (Exception e) {
            log.warn("Failed to invalidate AvengerMemory cache for {}:{}: {}", avenger, projectId, e.getMessage());
        }
    }

    private AvengerMemorySnapshot rebuild(AvengerType avenger, String projectId) {
        List<LearningEvent> events = learningEventRepository
                .findByAvengerAndProjectIdOrderByCreatedAtDesc(avenger, projectId,
                        PageRequest.of(0, maxEventsToScan()));

        if (events.isEmpty()) return AvengerMemorySnapshot.empty(avenger, projectId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LearningEvent e : events) {
            if (e.getEventType() == LearningEventType.AVENGER_VIOLATION_OBSERVED && e.getConventionRule() != null) {
                counts.merge(conventionKeyExtractor.extract(e.getConventionRule()), 1, Integer::sum);
            }
        }

        List<String> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(topViolations())
                .map(Map.Entry::getKey)
                .toList();

        List<String> recent = events.stream()
                .limit(recentIssues())
                .map(LearningEvent::getConventionRule)
                .filter(s -> s != null && !s.isBlank())
                .toList();

        String hint = buildTrendHint(avenger, counts, top);

        return new AvengerMemorySnapshot(avenger, projectId, events.size(), counts, top, recent, hint);
    }

    private String buildTrendHint(AvengerType avenger, Map<String, Integer> counts, List<String> top) {
        if (top.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : top) {
            int n = counts.getOrDefault(key, 0);
            sb.append(avenger.name()).append(" has seen '")
                    .append(key).append("' violated ").append(n).append(" time").append(n == 1 ? "" : "s")
                    .append(" on this project — be strict.\n");
        }
        return sb.toString();
    }

    private void cache(String key, AvengerMemorySnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            int ttlHours = brainProperties.cache() != null && brainProperties.cache().avengerMemoryTtlHours() > 0
                    ? brainProperties.cache().avengerMemoryTtlHours()
                    : 24;
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.warn("Failed to cache AvengerMemory for {}: {}", key, e.getMessage());
        }
    }

    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis unavailable for AvengerMemory key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private String cacheKey(AvengerType avenger, String projectId) {
        String safeProjectId = projectId == null ? "unknown" : projectId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return "brain:avenger:" + avenger.name() + ":memory:" + safeProjectId;
    }
}
