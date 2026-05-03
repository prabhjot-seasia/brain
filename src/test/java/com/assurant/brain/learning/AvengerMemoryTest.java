package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.observability.BrainMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AvengerMemory")
class AvengerMemoryTest {

    private LearningEventRepository learningEventRepository;
    @SuppressWarnings("unchecked")
    private RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private BrainMetrics brainMetrics;
    private AvengerMemory memory;

    @BeforeEach
    void setup() {
        learningEventRepository = mock(LearningEventRepository.class);
        brainMetrics = mock(BrainMetrics.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var cache = new BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null, null);
        memory = new AvengerMemory(learningEventRepository, redisTemplate, new ObjectMapper(),
                new ConventionKeyExtractor(props), props, brainMetrics);
    }

    @Test
    @DisplayName("returns empty snapshot when no events exist")
    void emptyWhenNoEvents() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(learningEventRepository.findByAvengerAndProjectIdOrderByCreatedAtDesc(
                any(AvengerType.class), anyString(), any(Pageable.class))).thenReturn(List.of());

        AvengerMemorySnapshot snapshot = memory.getMemory(AvengerType.HAWKEYE, "proj-1");
        assertThat(snapshot.isEmpty()).isTrue();
        verify(brainMetrics).recordAvengerMemoryMiss(AvengerType.HAWKEYE);
    }

    @Test
    @DisplayName("builds snapshot from repeated violation events")
    void buildsFromEvents() {
        when(valueOps.get(anyString())).thenReturn(null);
        LearningEvent e1 = event("Field injection detected");
        LearningEvent e2 = event("Field injection (@Autowired) detected");
        LearningEvent e3 = event("Uses @Slf4j");
        when(learningEventRepository.findByAvengerAndProjectIdOrderByCreatedAtDesc(
                eq(AvengerType.HAWKEYE), eq("proj-1"), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3));

        AvengerMemorySnapshot snapshot = memory.getMemory(AvengerType.HAWKEYE, "proj-1");

        assertThat(snapshot.totalEvents()).isEqualTo(3);
        assertThat(snapshot.violationCounts()).containsKeys("no-field-injection", "log4j2-usage");
        assertThat(snapshot.violationCounts().get("no-field-injection")).isEqualTo(2);
        assertThat(snapshot.trendHint()).contains("HAWKEYE");
        assertThat(snapshot.trendHint()).contains("no-field-injection");
    }

    @Test
    @DisplayName("records cache hit metric when Redis returns valid json")
    void recordsCacheHitMetric() throws Exception {
        String json = """
                {"avenger":"HAWKEYE","projectId":"proj-1","totalEvents":5,
                 "violationCounts":{"k":5},"topViolations":["k"],"recentIssues":[],"trendHint":"hint"}
                """;
        when(valueOps.get(anyString())).thenReturn(json);

        memory.getMemory(AvengerType.HAWKEYE, "proj-1");

        verify(brainMetrics).recordAvengerMemoryHit(AvengerType.HAWKEYE);
    }

    @Test
    @DisplayName("invalidate deletes Redis key")
    void invalidateDeletesKey() {
        memory.invalidate(AvengerType.HAWKEYE, "proj-1");
        verify(redisTemplate).delete("brain:avenger:HAWKEYE:memory:proj-1");
    }

    @Test
    @DisplayName("degrades gracefully when Redis is unavailable")
    void degradesGracefullyOnRedisFailure() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        when(learningEventRepository.findByAvengerAndProjectIdOrderByCreatedAtDesc(
                any(), anyString(), any(Pageable.class))).thenReturn(List.of());

        AvengerMemorySnapshot snapshot = memory.getMemory(AvengerType.HAWKEYE, "proj-1");

        assertThat(snapshot.isEmpty()).isTrue();
    }

    private LearningEvent event(String rule) {
        LearningEvent e = new LearningEvent();
        e.setAvenger(AvengerType.HAWKEYE);
        e.setProjectId("proj-1");
        e.setEventType(LearningEventType.AVENGER_VIOLATION_OBSERVED);
        e.setConventionRule(rule);
        return e;
    }
}
