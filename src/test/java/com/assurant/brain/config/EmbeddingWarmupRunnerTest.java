package com.assurant.brain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmbeddingWarmupRunner")
class EmbeddingWarmupRunnerTest {

    @Test
    @DisplayName("warmup calls embed once on startup")
    void warmupSuccess() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[1024]);
        var runner = new EmbeddingWarmupRunner(model);
        assertThatNoException().isThrownBy(runner::warmup);
        verify(model).embed(anyString());
    }

    @Test
    @DisplayName("warmup does not throw when embed fails")
    void warmupFailure() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new RuntimeException("model not loaded"));
        var runner = new EmbeddingWarmupRunner(model);
        assertThatNoException().isThrownBy(runner::warmup);
    }
}
