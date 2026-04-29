package com.assurant.brain.monitor;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.TokenUsageRecordRepository;
import com.assurant.brain.domain.TokenUsageRecord;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.observability.BrainMetrics;
import com.assurant.brain.util.TokenEstimator;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class TokenUsageTracker {

    private static final double DEFAULT_INPUT_TOKEN_RATE = 0.000003;
    private static final double DEFAULT_OUTPUT_TOKEN_RATE = 0.000015;

    private final TokenUsageRecordRepository repository;
    private final BrainMetrics brainMetrics;
    private final BrainProperties brainProperties;

    @Async
    public void track(String serviceName, LlmOperation operation, String projectId,
                      String inputText, String outputText, long latencyMs, boolean cached, String modelName) {
        try {
            int inputTokens = TokenEstimator.estimate(inputText);
            int outputTokens = TokenEstimator.estimate(outputText);
            double inputRate = brainProperties.cost() != null ? brainProperties.cost().inputTokenRate() : DEFAULT_INPUT_TOKEN_RATE;
            double outputRate = brainProperties.cost() != null ? brainProperties.cost().outputTokenRate() : DEFAULT_OUTPUT_TOKEN_RATE;
            double cost = cached ? 0.0 : (inputTokens * inputRate) + (outputTokens * outputRate);

            TokenUsageRecord record = new TokenUsageRecord();
            record.setServiceName(serviceName);
            record.setOperation(operation);
            record.setProjectId(projectId);
            record.setInputTokens(inputTokens);
            record.setOutputTokens(outputTokens);
            record.setCached(cached);
            record.setLatencyMs(latencyMs);
            record.setCostEstimate(cost);
            record.setModelName(modelName);
            repository.save(record);

            brainMetrics.recordTokensUsed(inputTokens + outputTokens);
            if (cached) {
                brainMetrics.recordCacheHit();
            } else {
                brainMetrics.recordCacheMiss();
                brainMetrics.llmLatencyTimer().record(Duration.ofMillis(latencyMs));
            }

            log.debug("Token usage: {}:{} input={} output={} cached={} cost=${} latency={}ms",
                    serviceName, operation, inputTokens, outputTokens, cached,
                    String.format("%.4f", cost), latencyMs);
        } catch (Exception e) {
            log.warn("Failed to track token usage for {}:{}: {}", serviceName, operation, e.getMessage());
        }
    }

    public void trackCacheHit(String serviceName, LlmOperation operation, String projectId,
                               String inputText, String cachedOutput) {
        track(serviceName, operation, projectId, inputText, cachedOutput, 0, true, "cache");
    }
}
