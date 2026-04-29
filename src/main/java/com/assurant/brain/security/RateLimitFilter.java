package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Log4j2
@Component
@org.springframework.context.annotation.Profile("!test")
public class RateLimitFilter extends OncePerRequestFilter {

    private final int llmRatePerMinute;
    private final int apiRatePerMinute;
    private final long windowMs;

    public RateLimitFilter(BrainProperties brainProperties) {
        BrainProperties.Security sec = brainProperties.security();
        this.llmRatePerMinute = sec != null && sec.llmRateLimitPerMinute() > 0 ? sec.llmRateLimitPerMinute() : 10;
        this.apiRatePerMinute = sec != null && sec.apiRateLimitPerMinute() > 0 ? sec.apiRateLimitPerMinute() : 60;
        this.windowMs = sec != null && sec.rateLimitWindowMs() > 0 ? sec.rateLimitWindowMs() : 60_000;
    }

    private static final String[] LLM_PATHS = {
            "/api/v1/analyze", "/api/v1/docs/generate", "/api/v1/jira/tickets/propose",
            "/api/v1/pr/create"
    };

    /**
     * Status-check / observation endpoints that must NOT count against the per-user
     * API rate limit. Polling clients (UI's useJobStream reconnects, MCP fallback,
     * recent-jobs panel) and the SSE stream itself can hit these many times per minute.
     */
    private static final String[] OBSERVATION_PATHS = {
            "/api/v1/jobs",
            "/actuator"
    };

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String userId = SecurityUtils.currentUserId();
        String path = request.getRequestURI();
        if (isObservationEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean isLlmPath = isLlmEndpoint(path);
        int limit = isLlmPath ? llmRatePerMinute : apiRatePerMinute;

        String bucketKey = userId + ":" + (isLlmPath ? "llm" : "api");
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> new Bucket(limit, windowMs));

        if (!bucket.tryConsume()) {
            log.warn("Rate limit exceeded for user={} path={} limit={}/min", userId, path, limit);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded. Try again in 60 seconds.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLlmEndpoint(String path) {
        for (String llmPath : LLM_PATHS) {
            if (path.startsWith(llmPath)) return true;
        }
        return false;
    }

    private boolean isObservationEndpoint(String path) {
        for (String p : OBSERVATION_PATHS) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    private static class Bucket {
        private final int maxTokens;
        private final long windowMs;
        private final AtomicInteger tokens;
        private final AtomicLong windowStart;

        Bucket(int maxTokens, long windowMs) {
            this.maxTokens = maxTokens;
            this.windowMs = windowMs;
            this.tokens = new AtomicInteger(maxTokens);
            this.windowStart = new AtomicLong(System.currentTimeMillis());
        }

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start > windowMs && windowStart.compareAndSet(start, now)) {
                tokens.set(maxTokens);
            }
            return tokens.decrementAndGet() >= 0;
        }
    }
}
