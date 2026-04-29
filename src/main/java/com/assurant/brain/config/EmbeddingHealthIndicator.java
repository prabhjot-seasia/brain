package com.assurant.brain.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("embedding")
@Profile("!test")
@RequiredArgsConstructor
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final EmbeddingModel embeddingModel;

    @Override
    public Health health() {
        try {
            float[] vec = embeddingModel.embed("health-check");
            return Health.up()
                    .withDetail("dimensions", vec.length)
                    .withDetail("model", embeddingModel.getClass().getSimpleName())
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("model", embeddingModel.getClass().getSimpleName())
                    .build();
        }
    }
}
