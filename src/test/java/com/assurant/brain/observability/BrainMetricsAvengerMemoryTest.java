package com.assurant.brain.observability;

import com.assurant.brain.enums.AvengerType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrainMetrics.recordAvengerMemory")
class BrainMetricsAvengerMemoryTest {

    @Test
    @DisplayName("records tagged hit and miss counters per Avenger")
    void recordsTagged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BrainMetrics metrics = new BrainMetrics(registry);

        metrics.recordAvengerMemoryHit(AvengerType.HAWKEYE);
        metrics.recordAvengerMemoryHit(AvengerType.HAWKEYE);
        metrics.recordAvengerMemoryMiss(AvengerType.STARK);

        assertThat(registry.counter("brain.avenger.memory.hits", "avenger", "HAWKEYE").count()).isEqualTo(2.0);
        assertThat(registry.counter("brain.avenger.memory.misses", "avenger", "STARK").count()).isEqualTo(1.0);
    }
}
