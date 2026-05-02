package com.assurant.brain.config.properties;

import com.assurant.brain.enums.InjectionClassifierType;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brain")
public record BrainProperties(
        Llm llm,
        Embed embed,
        Rag rag,
        Chunk chunk,
        GitHub github,
        Jira jira,
        Intake intake,
        Ci ci,
        Cache cache,
        Security security,
        Cost cost,
        Guardrails guardrails,
        Autodev autodev,
        Clarifier clarifier,
        Learning learning,
        Codegen codegen,
        Sandbox sandbox,
        Hawkeye hawkeye,
        Docs docs
) {
    public record Llm(String planModel, String extractModel, String provider, double charsPerToken) {}

    public record Embed(String provider, String model, int dimensions, String ollamaBaseUrl) {}

    public record Rag(int topKCode, int topKDoc, int maxContextTokens,
                      double docTrustWeight, double codeTrustWeight,
                      int maxConventions, int maxGraphClasses) {}

    public record Chunk(int maxChars, int overlapChars) {}

    public record GitHub(String token, String apiBaseUrl, int maxSelfReviewIterations) {}

    public record Jira(String oauthClientId, String oauthClientSecret, String oauthRedirectUri, String tokenEncryptionKey,
                        String webhookSecret, String labelPrefix, String connectedUserId) {}

    public record Intake(long maxFileSizeMb) {}

    public record Ci(String webhookSecret, int maxRemediationAttempts, double learningWeightDelta,
                     double minTrustWeight, double maxTrustWeight, int maxFailureLogChars) {}

    public record Cache(double semanticThreshold, int planTtlMinutes, int conventionTtlMinutes,
                         boolean enabled, int avengerMemoryTtlHours, int maxMemoryHintTokens,
                         int patternCacheTtlHours) {}

    public record Security(String jwtSecret, int jwtTtlMinutes, String corsAllowedOrigins,
                           int llmRateLimitPerMinute, int apiRateLimitPerMinute, long rateLimitWindowMs,
                           boolean enforceProjectMembership) {}

    public record Cost(double inputTokenRate, double outputTokenRate) {}

    public record Guardrails(
            int maxInputChars,
            int maxPromptChars,
            int maxOutputChars,
            boolean blockOnPiiDetection,
            boolean strictSchema,
            InjectionClassifierType injectionClassifier
    ) {}

    public record Autodev(
            double confidenceThreshold,
            int maxProjects,
            int maxPlanNodes,
            int blastRadiusThreshold,
            boolean requireSandbox
    ) {}

    public record Clarifier(
            double confidenceAverageThreshold,
            double confidencePerDimensionFloor,
            int firstRoundMinQuestions,
            int maxQuestionsPerRound,
            int maxRounds
    ) {}

    public record Learning(
            int maxEventsToScan,
            int topViolationsCount,
            int recentIssuesCount,
            int violationThreshold,
            int maxDiffChars,
            int maxFewShotExamples,
            int conventionKeyMaxLength
    ) {}

    public record Codegen(int smallChangeMaxFiles) {}

    public record Sandbox(boolean enabled, long timeoutSeconds, String dockerImage,
                          long memoryMb, double cpus) {}

    public record Hawkeye(String asffProductArn, String awsAccountId, String region) {}

    public record Docs(String mermaidCliPath,
                       int cacheTtlMinutes,
                       int maxPdfBytes,
                       int maxPrsIncluded,
                       int mermaidTimeoutSeconds) {}
}
