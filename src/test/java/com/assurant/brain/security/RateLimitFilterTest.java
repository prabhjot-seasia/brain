package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private static BrainProperties propsWith(int llm, int api, long windowMs) {
        var sec = new BrainProperties.Security("secret", 60, "*", llm, api, windowMs, false);
        return new BrainProperties(null, null, null, null, null, null, null, null, null, sec, null, null, null, null, null, null, null, null, new BrainProperties.Docs("mmdc", 60, 0, 0, 5), null);
    }

    private RateLimitFilter filter;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private FilterChain chain;
    private StringWriter responseBody;

    @BeforeEach
    void setup() throws Exception {
        filter = new RateLimitFilter(propsWith(2, 5, 60_000));
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    @DisplayName("API endpoint allows requests under the API limit")
    void underApiLimitPasses() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/projects");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            for (int i = 0; i < 5; i++) {
                filter.doFilter(req, resp, chain);
            }
        }
        verify(chain, times(5)).doFilter(req, resp);
        verify(resp, never()).setStatus(429);
    }

    @Test
    @DisplayName("LLM endpoint returns 429 with Retry-After once limit exceeded")
    void llmLimitTriggers429() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/analyze");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            filter.doFilter(req, resp, chain);
            filter.doFilter(req, resp, chain);
            filter.doFilter(req, resp, chain);
        }
        verify(resp).setStatus(429);
        verify(resp).setHeader("Retry-After", "60");
        verify(chain, times(2)).doFilter(req, resp);
        assertThat(responseBody.toString()).contains("Rate limit exceeded");
    }

    @Test
    @DisplayName("Different users have independent buckets")
    void perUserBuckets() throws Exception {
        when(req.getRequestURI()).thenReturn("/api/v1/analyze");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            filter.doFilter(req, resp, chain);
            filter.doFilter(req, resp, chain);
            mocked.when(SecurityUtils::currentUserId).thenReturn("bob");
            filter.doFilter(req, resp, chain);
            filter.doFilter(req, resp, chain);
        }
        verify(chain, times(4)).doFilter(req, resp);
        verify(resp, never()).setStatus(429);
    }

    @Test
    @DisplayName("LLM and API buckets are independent for same user")
    void perBucketIsolation() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            when(req.getRequestURI()).thenReturn("/api/v1/analyze");
            filter.doFilter(req, resp, chain);
            filter.doFilter(req, resp, chain);
            // LLM bucket exhausted but API bucket fresh
            when(req.getRequestURI()).thenReturn("/api/v1/projects");
            for (int i = 0; i < 5; i++) {
                filter.doFilter(req, resp, chain);
            }
        }
        verify(resp, never()).setStatus(429);
        verify(chain, times(7)).doFilter(req, resp);
    }


    @Test
    @DisplayName("Observation endpoints (/api/v1/jobs, /actuator) are exempt from rate limit")
    void observationEndpointsExempt() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            // hammer /jobs/* and /actuator/* well past both buckets
            for (int i = 0; i < 50; i++) {
                when(req.getRequestURI()).thenReturn("/api/v1/jobs/" + java.util.UUID.randomUUID());
                filter.doFilter(req, resp, chain);
                when(req.getRequestURI()).thenReturn("/api/v1/jobs?projectId=p&limit=20");
                filter.doFilter(req, resp, chain);
                when(req.getRequestURI()).thenReturn("/api/v1/jobs/stream/" + java.util.UUID.randomUUID());
                filter.doFilter(req, resp, chain);
                when(req.getRequestURI()).thenReturn("/actuator/health");
                filter.doFilter(req, resp, chain);
            }
        }
        verify(resp, never()).setStatus(429);
        verify(chain, atLeastOnce()).doFilter(req, resp);
    }

    @Test
    @DisplayName("Filter falls back to defaults when security props are null")
    void defaultsFromNullProps() throws Exception {
        var emptyProps = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        RateLimitFilter f = new RateLimitFilter(emptyProps);
        when(req.getRequestURI()).thenReturn("/api/v1/projects");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::currentUserId).thenReturn("alice");
            f.doFilter(req, resp, chain);
        }
        verify(chain, atLeastOnce()).doFilter(req, resp);
    }
}
