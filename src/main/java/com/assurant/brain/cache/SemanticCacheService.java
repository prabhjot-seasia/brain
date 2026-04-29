package com.assurant.brain.cache;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final String CACHE_PREFIX = "brain:llm:";

    private final RedisTemplate<String, String> redisTemplate;
    private final BrainProperties brainProperties;

    public Optional<String> get(String service, String projectId, String promptContent) {
        if (!isCacheEnabled()) return Optional.empty();

        String key = buildKey(service, projectId, promptContent);

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                int savedTokens = TokenEstimator.estimate(promptContent);
                log.info("Semantic cache HIT for {}:{} — saved ~{} input tokens", service, projectId, savedTokens);
                return Optional.of(cached);
            }
        } catch (Exception e) {
            log.warn("Redis cache read failed for {}:{} — proceeding without cache: {}", service, projectId, e.getMessage());
        }

        return Optional.empty();
    }

    public void put(String service, String projectId, String promptContent, String response, int ttlMinutes) {
        if (!isCacheEnabled()) return;

        String key = buildKey(service, projectId, promptContent);

        try {
            redisTemplate.opsForValue().set(key, response, ttlMinutes, TimeUnit.MINUTES);
            log.debug("Cached LLM response for {}:{} with TTL={}m", service, projectId, ttlMinutes);
        } catch (Exception e) {
            log.warn("Redis cache write failed for {}:{}: {}", service, projectId, e.getMessage());
        }
    }

    public void evictByProject(String projectId) {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*:" + projectId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Evicted {} cache entries for project={}", keys.size(), projectId);
            }
        } catch (Exception e) {
            log.warn("Redis cache eviction failed for project={}: {}", projectId, e.getMessage());
        }
    }

    private boolean isCacheEnabled() {
        return brainProperties.cache() != null && brainProperties.cache().enabled();
    }

    private String buildKey(String service, String projectId, String promptContent) {
        String contentHash = sha256(promptContent);
        return CACHE_PREFIX + service + ":" + projectId + ":" + contentHash;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
