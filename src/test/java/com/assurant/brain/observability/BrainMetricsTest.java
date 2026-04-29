package com.assurant.brain.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrainMetrics")
class BrainMetricsTest {

    private SimpleMeterRegistry registry;
    private BrainMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        metrics = new BrainMetrics(registry);
    }

    @Test
    @DisplayName("records token usage")
    void recordsTokens() {
        metrics.recordTokensUsed(500);
        assertThat(registry.counter("brain.tokens.used").count()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("records cache hits and misses")
    void recordsCacheMetrics() {
        metrics.recordCacheHit();
        metrics.recordCacheHit();
        metrics.recordCacheMiss();
        assertThat(registry.counter("brain.cache.hits").count()).isEqualTo(2.0);
        assertThat(registry.counter("brain.cache.misses").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("records AST validations")
    void recordsAstValidations() {
        metrics.recordAstValidation(true);
        metrics.recordAstValidation(false);
        assertThat(registry.counter("brain.ast.validations").count()).isEqualTo(2.0);
        assertThat(registry.counter("brain.ast.failures").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("records PR metrics")
    void recordsPrMetrics() {
        metrics.recordPrCreated();
        metrics.recordPrFailed();
        assertThat(registry.counter("brain.pr.created").count()).isEqualTo(1.0);
        assertThat(registry.counter("brain.pr.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("llm latency timer is registered")
    void llmLatencyTimerExists() {
        assertThat(metrics.llmLatencyTimer()).isNotNull();
        assertThat(registry.timer("brain.llm.latency")).isNotNull();
    }
}
