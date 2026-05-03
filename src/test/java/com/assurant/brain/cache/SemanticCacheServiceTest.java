package com.assurant.brain.cache;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticCacheService")
class SemanticCacheServiceTest {

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    SemanticCacheService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var cache = new BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null, null);
        service = new SemanticCacheService(redisTemplate, props);
    }

    @Test
    @DisplayName("get returns cached value on cache hit")
    void getCacheHit() {
        when(valueOps.get(anyString())).thenReturn("cached response");
        Optional<String> result = service.get("PlannerService", "proj-1", "some prompt content");
        assertThat(result).isPresent().contains("cached response");
    }

    @Test
    @DisplayName("get returns empty on cache miss")
    void getCacheMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        Optional<String> result = service.get("PlannerService", "proj-1", "some prompt");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get returns empty when cache is disabled")
    void getCacheDisabled() {
        var disabledCache = new BrainProperties.Cache(0.90, 30, 5, false, 24, 300, 24);
        var disabledProps = new BrainProperties(null, null, null, null, null, null, null, null, disabledCache, null, null, null, null, null, null, null, null, null, null, null);
        var disabledService = new SemanticCacheService(redisTemplate, disabledProps);

        Optional<String> result = disabledService.get("PlannerService", "proj-1", "prompt");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get swallows Redis exceptions")
    void getSwallowsRedisException() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis unavailable"));
        Optional<String> result = service.get("PlannerService", "proj-1", "some prompt");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("put stores value with TTL")
    void putStoresWithTtl() {
        service.put("PlannerService", "proj-1", "some prompt", "response text", 30);
        verify(valueOps).set(anyString(), eq("response text"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("put is a no-op when cache is disabled")
    void putDisabled() {
        var disabledCache = new BrainProperties.Cache(0.90, 30, 5, false, 24, 300, 24);
        var disabledProps = new BrainProperties(null, null, null, null, null, null, null, null, disabledCache, null, null, null, null, null, null, null, null, null, null, null);
        var disabledService = new SemanticCacheService(redisTemplate, disabledProps);

        disabledService.put("PlannerService", "proj-1", "prompt", "response", 30);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("cache key is deterministic for same input")
    void deterministicCacheKey() {
        when(valueOps.get(anyString())).thenReturn("hit");
        service.get("PlannerService", "proj-1", "deterministic prompt");
        service.get("PlannerService", "proj-1", "deterministic prompt");
        verify(valueOps, times(2)).get(argThat((String k) -> k.startsWith("brain:llm:")));
    }

    @Test
    @DisplayName("evictByProject deletes matching keys")
    void evictByProject() {
        when(redisTemplate.keys(anyString())).thenReturn(Set.of("brain:llm:svc:proj-1:abc"));
        service.evictByProject("proj-1");
        verify(redisTemplate).delete(anyCollection());
    }

    @Test
    @DisplayName("evictByProject is safe when no keys match")
    void evictByProjectNoKeys() {
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
        service.evictByProject("proj-1");
        verify(redisTemplate, never()).delete(anyCollection());
    }
}
