package com.assurant.brain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EmbeddingHealthIndicator")
class EmbeddingHealthIndicatorTest {

    @Test
    @DisplayName("reports UP with dimensions when embedding succeeds")
    void healthUp() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[1024]);
        var indicator = new EmbeddingHealthIndicator(model);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("dimensions", 1024);
    }

    @Test
    @DisplayName("reports DOWN when embedding throws")
    void healthDown() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("Ollama not running"));
        var indicator = new EmbeddingHealthIndicator(model);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
