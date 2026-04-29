package com.assurant.brain.ci;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.monitor.TokenUsageTracker;
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

@Log4j2
@Service
@RequiredArgsConstructor
public class CiFailureParser {

    private int maxLogChars() { return brainProperties.ci() != null ? brainProperties.ci().maxFailureLogChars() : 15_000; }

    private static final String SYSTEM_PROMPT = """
            You are a CI/CD failure analysis expert. Given CI workflow logs or job output,
            extract the specific failures that caused the build/test to fail.

            For each failure, provide:
            - A concise one-line description of what failed
            - The file and line number if available
            - Whether it's a compilation error, test failure, linting error, or other

            Return a JSON object:
            {
              "failures": [
                "TestFoo.testBar: AssertionError - expected 42 but got 0 (src/test/Foo.java:15)",
                "CompileError: cannot find symbol MyService (src/main/java/MyService.java:3)"
              ],
              "summary": "2 test failures and 1 compile error",
              "category": "TEST_FAILURE" | "COMPILE_ERROR" | "LINT_ERROR" | "MIXED"
            }

            Return ONLY valid JSON — no markdown fences, no explanation text.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;

    public FailureAnalysis parse(String ciLogs) {
        log.info("Parsing CI failure logs ({} chars)", ciLogs.length());

        int maxChars = maxLogChars();
        String truncatedLogs = ciLogs.length() > maxChars
                ? ciLogs.substring(ciLogs.length() - maxChars)
                : ciLogs;

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(truncatedLogs)
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("CiFailureParser", LlmOperation.CI_PARSE, null,
                truncatedLogs, rawResponse, latencyMs, false, modelName);

        return parseResult(rawResponse);
    }

    private FailureAnalysis parseResult(String rawJson) {
        String json = LlmJsonParser.stripFences(rawJson);

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<String> failures = (List<String>) parsed.getOrDefault("failures", List.of());
            String summary = (String) parsed.getOrDefault("summary", "");
            String category = (String) parsed.getOrDefault("category", "MIXED");
            return new FailureAnalysis(failures, summary, category);
        } catch (Exception e) {
            log.error("Failed to parse CI failure analysis: {}", e.getMessage());
            return new FailureAnalysis(
                    List.of("Unable to parse CI logs — raw analysis failed: " + e.getMessage()),
                    "Parse failure", "MIXED");
        }
    }

    public record FailureAnalysis(List<String> failures, String summary, String category) {}
}
