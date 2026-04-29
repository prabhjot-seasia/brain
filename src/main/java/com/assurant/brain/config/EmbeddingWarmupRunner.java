package com.assurant.brain.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@Profile("!test")
@RequiredArgsConstructor
public class EmbeddingWarmupRunner {

    private final EmbeddingModel embeddingModel;

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        try {
            long start = System.currentTimeMillis();
            float[] vec = embeddingModel.embed("warmup");
            log.info("Embedding model warmup OK — provider={}, dimensions={}, latency={}ms",
                    embeddingModel.getClass().getSimpleName(),
                    vec.length,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Embedding model warmup failed — first real call will pay the loading cost. Reason: {}",
                    e.getMessage());
        }
    }
}
