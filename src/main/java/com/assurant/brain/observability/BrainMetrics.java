package com.assurant.brain.observability;

import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.enums.RailType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class BrainMetrics {

    private final Counter tokensUsed;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter astValidations;
    private final Counter astFailures;
    private final Counter prCreated;
    private final Counter prFailed;
    private final Timer llmLatency;
    private final MeterRegistry registry;

    public BrainMetrics(MeterRegistry registry) {
        this.registry = registry;
        tokensUsed = Counter.builder("brain.tokens.used")
                .description("Total estimated LLM tokens consumed")
                .register(registry);
        cacheHits = Counter.builder("brain.cache.hits")
                .description("Semantic cache hits")
                .register(registry);
        cacheMisses = Counter.builder("brain.cache.misses")
                .description("Semantic cache misses")
                .register(registry);
        astValidations = Counter.builder("brain.ast.validations")
                .description("AST validations performed")
                .register(registry);
        astFailures = Counter.builder("brain.ast.failures")
                .description("AST validations that failed")
                .register(registry);
        prCreated = Counter.builder("brain.pr.created")
                .description("Pull requests created successfully")
                .register(registry);
        prFailed = Counter.builder("brain.pr.failed")
                .description("Pull requests that failed")
                .register(registry);
        llmLatency = Timer.builder("brain.llm.latency")
                .description("LLM call latency")
                .publishPercentileHistogram(true)
                .register(registry);
    }

    public void recordTokensUsed(long tokens) { tokensUsed.increment(tokens); }
    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordAstValidation(boolean passed) {
        astValidations.increment();
        if (!passed) astFailures.increment();
    }
    public void recordPrCreated() { prCreated.increment(); }
    public void recordPrFailed() { prFailed.increment(); }
    public Timer llmLatencyTimer() { return llmLatency; }

    public void recordGuardrailOutcome(RailType rail, RailDecision decision) {
        Counter.builder("brain.guardrail.outcome")
                .tag("rail", rail.name())
                .tag("decision", decision.name())
                .description("Guardrail evaluations by rail type and decision")
                .register(registry)
                .increment();
    }

    public void recordAvengerMemoryHit(AvengerType avenger) {
        Counter.builder("brain.avenger.memory.hits")
                .tag("avenger", avenger.name())
                .description("AvengerMemory cache hits (Redis)")
                .register(registry)
                .increment();
    }

    public void recordAvengerMemoryMiss(AvengerType avenger) {
        Counter.builder("brain.avenger.memory.misses")
                .tag("avenger", avenger.name())
                .description("AvengerMemory cache misses (rebuilt from DB)")
                .register(registry)
                .increment();
    }
}
