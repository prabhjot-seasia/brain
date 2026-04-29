package com.assurant.brain.monitor;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.TokenUsageRecordRepository;
import com.assurant.brain.domain.TokenUsageRecord;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.observability.BrainMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenUsageTracker")
class TokenUsageTrackerTest {

    @Mock
    TokenUsageRecordRepository repository;

    TokenUsageTracker tracker;

    @BeforeEach
    void setup() {
        BrainMetrics metrics = new BrainMetrics(new SimpleMeterRegistry());
        var cost = new BrainProperties.Cost(0.000003, 0.000015);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, cost, null, null, null, null, null, null, null, null);
        tracker = new TokenUsageTracker(repository, metrics, props);
    }

    @Test
    @DisplayName("track saves a record with correct fields")
    void trackSavesRecord() {
        tracker.track("PlannerService", LlmOperation.PLAN, "proj-1",
                "input text here", "output text", 250L, false, "claude-sonnet");

        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(repository).save(captor.capture());

        TokenUsageRecord saved = captor.getValue();
        assertThat(saved.getServiceName()).isEqualTo("PlannerService");
        assertThat(saved.getOperation()).isEqualTo(LlmOperation.PLAN);
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getLatencyMs()).isEqualTo(250L);
        assertThat(saved.isCached()).isFalse();
        assertThat(saved.getModelName()).isEqualTo("claude-sonnet");
        assertThat(saved.getInputTokens()).isGreaterThan(0);
        assertThat(saved.getOutputTokens()).isGreaterThan(0);
        assertThat(saved.getCostEstimate()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("track sets zero cost for cached responses")
    void trackCachedResponseHasZeroCost() {
        tracker.track("ClarifierService", LlmOperation.CLARIFY, "proj-2",
                "prompt text", "cached response", 0L, true, "cache");

        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getCostEstimate()).isEqualTo(0.0);
        assertThat(captor.getValue().isCached()).isTrue();
    }

    @Test
    @DisplayName("trackCacheHit delegates to track with cached=true")
    void trackCacheHit() {
        tracker.trackCacheHit("SemanticCache", LlmOperation.PLAN, "proj-3",
                "prompt", "cached output");

        ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().isCached()).isTrue();
        assertThat(captor.getValue().getModelName()).isEqualTo("cache");
        assertThat(captor.getValue().getLatencyMs()).isEqualTo(0L);
    }

    @Test
    @DisplayName("track swallows repository exceptions without throwing")
    void trackDoesNotThrowOnRepositoryFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB down"));

        tracker.track("PlannerService", LlmOperation.PLAN, "proj-4",
                "input", "output", 100L, false, "model");
    }
}
