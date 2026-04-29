package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.rails.OutputSchemaRail;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.observability.BrainMetrics;
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
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class CodeReviewService {

    private static final String SYSTEM_PROMPT = """
            You are a senior code reviewer performing an automated review of LLM-generated code.

            Review the provided files against the implementation plan and report issues.

            Check for:
            1. Compilation errors — missing imports, syntax errors, type mismatches
            2. Convention violations — naming, patterns, structure must match the plan
            3. Security issues — injection, hardcoded secrets, missing input validation
            4. Logic errors — incorrect algorithms, missing edge cases, wrong control flow
            5. Missing error handling — catch blocks that swallow exceptions, missing validation
            6. Dead code — unused imports, unused variables, unreachable code
            7. Hardcoded values — magic strings, URLs, or numbers that should be config/constants

            Return a JSON object:
            {
              "verdict": "PASS" | "FAIL",
              "issues": ["issue description 1", "issue description 2"],
              "summary": "one-line overall assessment"
            }

            If the code is clean and correct, return verdict "PASS" with an empty issues array.
            Return ONLY valid JSON — no markdown fences, no explanation text.
            """;

    private static final String CODE_REVIEW_SCHEMA = "schemas/code-review-result.json";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final AstValidator astValidator;
    private final ConventionChecker conventionChecker;
    private final BrainMetrics brainMetrics;
    private final RailChain railChain;

    public ReviewResult review(Map<String, String> generatedFiles, String planJson) {
        log.info("Self-reviewing {} generated files", generatedFiles.size());

        AstValidator.ValidationResult astResult = astValidator.validate(generatedFiles);
        brainMetrics.recordAstValidation(astResult.passed());
        if (!astResult.passed()) {
            log.info("AST validation failed with {} issues — skipping LLM review to save tokens", astResult.issues().size());
            return new ReviewResult(false, astResult.issues(), "AST validation failed — structural issues found");
        }

        List<String> conventionViolations = conventionChecker.check(generatedFiles);
        if (!conventionViolations.isEmpty()) {
            log.info("Convention check found {} violations — skipping LLM review to save tokens", conventionViolations.size());
            return new ReviewResult(false, conventionViolations, "Convention violations found at AST level");
        }

        String filesFormatted = generatedFiles.entrySet().stream()
                .map(e -> "=== " + e.getKey() + " ===\n" + e.getValue())
                .collect(Collectors.joining("\n\n"));

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(null, "CodeReviewService", filesFormatted));
        String sanitizedFiles = preLlm.sanitized();

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        --- IMPLEMENTATION PLAN ---
                        %s

                        --- GENERATED FILES ---
                        %s

                        Review the code now.
                        """.formatted(planJson, sanitizedFiles))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("CodeReviewService", LlmOperation.CODE_REVIEW, null,
                planJson, rawResponse, latencyMs, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(null, "CodeReviewService", rawResponse,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, CODE_REVIEW_SCHEMA)));

        return parseReviewResult(rawResponse);
    }

    private ReviewResult parseReviewResult(String rawJson) {
        String json = LlmJsonParser.stripFences(rawJson);

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            String verdict = (String) parsed.getOrDefault("verdict", "FAIL");
            @SuppressWarnings("unchecked")
            List<String> issues = (List<String>) parsed.getOrDefault("issues", List.of());
            String summary = (String) parsed.getOrDefault("summary", "");
            return new ReviewResult("PASS".equalsIgnoreCase(verdict), issues, summary);
        } catch (Exception e) {
            log.error("Failed to parse review result: {}", e.getMessage());
            return new ReviewResult(false, List.of("Failed to parse self-review result: " + e.getMessage()), "Review parsing failed");
        }
    }

    public record ReviewResult(boolean passed, List<String> issues, String summary) {}
}
