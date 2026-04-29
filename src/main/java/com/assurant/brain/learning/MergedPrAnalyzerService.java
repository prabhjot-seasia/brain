package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.github.GitHubClient;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.GitHubUrlParser;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class MergedPrAnalyzerService {

    private int maxDiffChars() { return brainProperties.learning() != null ? brainProperties.learning().maxDiffChars() : 10_000; }

    private static final String SYSTEM_PROMPT = """
            You are a code review analyst comparing generated code vs. final merged code.

            Given the original generated files and the PR diff (showing what the human reviewer changed),
            analyze which project conventions were:
            1. FOLLOWED — the generated code correctly applied these conventions and they survived review
            2. VIOLATED — the human reviewer changed code in ways that indicate a convention was not correctly applied

            Return a JSON object:
            {
              "followed": ["convention keyword 1", "convention keyword 2"],
              "violated": ["convention keyword 3"],
              "summary": "one-line assessment of code quality"
            }

            Convention keywords should be short identifiers like "constructor-injection", "error-handling",
            "naming-convention", "enum-usage", "input-validation", etc.

            Return ONLY valid JSON — no markdown fences, no explanation text.
            """;

    private final ChatModel chatModel;
    private final GitHubClient gitHubClient;
    private final PullRequestRecordRepository prRecordRepository;
    private final ConventionLearner conventionLearner;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;

    public List<LearningEvent> analyzeAndLearn(UUID prRecordId, String projectId) {
        PullRequestRecord prRecord = prRecordRepository.findById(prRecordId)
                .orElseThrow(() -> new IllegalArgumentException("PR record not found: " + prRecordId));

        if (prRecord.getPrNumber() == null) {
            throw new IllegalStateException("PR record has no PR number — cannot analyze merged diff.");
        }

        GitHubUrlParser.OwnerRepo ownerRepo = GitHubUrlParser.parse(prRecord.getRepoUrl());
        String diff = gitHubClient.getPullRequestDiff(
                ownerRepo.owner(), ownerRepo.repo(), prRecord.getPrNumber());

        Map<String, String> generatedFiles = prRecord.getGeneratedFiles();
        if (generatedFiles == null || generatedFiles.isEmpty()) {
            log.warn("No generated files to compare for PR record={}", prRecordId);
            return List.of();
        }

        ConventionAnalysis analysis = analyzeConventions(generatedFiles, diff);

        return conventionLearner.adjustWeights(
                projectId, prRecordId, analysis.followed(), analysis.violated());
    }

    private ConventionAnalysis analyzeConventions(Map<String, String> generatedFiles, String diff) {
        StringBuilder filesFormatted = new StringBuilder();
        generatedFiles.forEach((path, content) ->
                filesFormatted.append("=== ").append(path).append(" ===\n")
                        .append(content).append("\n\n"));

        int maxChars = maxDiffChars();
        String truncatedDiff = diff.length() > maxChars ? diff.substring(0, maxChars) : diff;

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        --- ORIGINAL GENERATED FILES ---
                        %s

                        --- PR DIFF (human reviewer changes) ---
                        %s

                        Analyze which conventions were followed vs. violated.
                        """.formatted(filesFormatted, truncatedDiff))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("MergedPrAnalyzerService", LlmOperation.MERGE_ANALYZE, null,
                filesFormatted.toString(), rawResponse, latencyMs, false, modelName);

        return parseAnalysis(rawResponse);
    }

    private ConventionAnalysis parseAnalysis(String rawJson) {
        String json = LlmJsonParser.stripFences(rawJson);

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<String> followed = (List<String>) parsed.getOrDefault("followed", List.of());
            @SuppressWarnings("unchecked")
            List<String> violated = (List<String>) parsed.getOrDefault("violated", List.of());
            return new ConventionAnalysis(followed, violated);
        } catch (Exception e) {
            log.error("Failed to parse convention analysis: {}", e.getMessage());
            return new ConventionAnalysis(List.of(), List.of());
        }
    }

    private record ConventionAnalysis(List<String> followed, List<String> violated) {}
}
