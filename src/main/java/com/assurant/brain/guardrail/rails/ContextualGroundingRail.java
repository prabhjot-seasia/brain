package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.assurant.brain.monitor.TokenUsageTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Log4j2
@Component
@RequiredArgsConstructor
public class ContextualGroundingRail implements Rail {

    private final BrainProperties brainProperties;
    private final TokenUsageTracker tokenUsageTracker;

    public static final String METADATA_GROUNDING_SOURCES = "groundingSources";
    public static final String METADATA_GROUNDING_THRESHOLD = "groundingThreshold";

    private static final int PRIORITY = 60;
    private static final double DEFAULT_THRESHOLD = 0.7;
    private static final int MIN_TOKEN_LENGTH = 4;

    private static final String BEDROCK_PROVIDER = "bedrock";

    private String provider() {
        if (brainProperties.llm() == null) return null;
        return brainProperties.llm().provider();
    }

    @Override
    public RailResult apply(RailContext context) {
        if (!BEDROCK_PROVIDER.equalsIgnoreCase(provider())) return RailResult.pass(type());

        String output = context.rawOutput();
        if (output == null || output.isBlank()) return RailResult.pass(type());

        Object sourcesMeta = context.metadata() == null ? null : context.metadata().get(METADATA_GROUNDING_SOURCES);
        if (!(sourcesMeta instanceof List<?> sources) || sources.isEmpty()) return RailResult.pass(type());

        double threshold = parseThreshold(context.metadata() == null ? null : context.metadata().get(METADATA_GROUNDING_THRESHOLD));
        long startMs = System.currentTimeMillis();
        double score = computeGroundingScore(output, sources);

        log.debug("Grounding score for service={} score={} threshold={}", context.sourceService(), score, threshold);
        try {
            String inputDigest = "outputLength=" + output.length() + " sources=" + sources.size();
            String summary = "score=" + String.format("%.3f", score)
                    + " threshold=" + String.format("%.3f", threshold)
                    + " sources=" + sources.size();
            tokenUsageTracker.track("ContextualGroundingRail", LlmOperation.GROUNDING_CHECK,
                    context.projectId(), inputDigest, summary,
                    System.currentTimeMillis() - startMs, false, "local-token-overlap");
        } catch (RuntimeException e) {
            log.debug("Grounding token tracking failed: {}", e.getMessage());
        }
        if (score < threshold) {
            return RailResult.block(type(), List.of(
                    "Response grounding score " + String.format("%.2f", score)
                            + " is below threshold " + String.format("%.2f", threshold)
                            + " — claims unsupported by retrieved context."));
        }
        return RailResult.pass(type());
    }

    private double parseThreshold(Object thresholdMeta) {
        if (thresholdMeta instanceof Number n) return n.doubleValue();
        if (thresholdMeta instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        return DEFAULT_THRESHOLD;
    }

    private double computeGroundingScore(String output, List<?> sources) {
        Set<String> outputTokens = tokenize(output);
        if (outputTokens.isEmpty()) return 1.0;

        Set<String> sourceTokens = new HashSet<>();
        for (Object src : sources) {
            if (src != null) sourceTokens.addAll(tokenize(src.toString()));
        }
        if (sourceTokens.isEmpty()) return 0.0;

        long supported = outputTokens.stream().filter(sourceTokens::contains).count();
        return (double) supported / outputTokens.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String t : text.toLowerCase().split("[^a-z0-9_]+")) {
            if (t.length() >= MIN_TOKEN_LENGTH) tokens.add(t);
        }
        return tokens;
    }

    @Override
    public RailPhase phase() { return RailPhase.POST_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.GROUNDING; }
}
