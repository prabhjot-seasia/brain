package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.monitor.TokenUsageTracker;
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
public class DiffGenerator {

    private int smallChangeMaxFiles() { return brainProperties.codegen() != null ? brainProperties.codegen().smallChangeMaxFiles() : 3; }

    private static final String DIFF_SYSTEM_PROMPT = """
            You are an expert software engineer generating a minimal unified diff.

            Given the existing file content and the change description, produce a unified diff
            that applies the change. Format:

            --- a/path/to/File.java
            +++ b/path/to/File.java
            @@ -startLine,count +startLine,count @@
             context line
            -removed line
            +added line
             context line

            Rules:
            1. Include 3 lines of context before and after each hunk
            2. Only include hunks that change — do not repeat unchanged sections
            3. Follow all project conventions from the provided list
            4. Return ONLY the unified diff — no explanation, no markdown fences
            """;

    private final ChatModel chatModel;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;

    public boolean isSmallChange(Map<String, Object> plan) {
        if (plan == null) return false;

        Object affectedFiles = plan.get("affectedFiles");
        Object steps = plan.get("steps");

        if (affectedFiles instanceof List<?> files && steps instanceof List<?> stepList) {
            return files.size() <= smallChangeMaxFiles() && stepList.size() <= files.size();
        }

        return false;
    }

    public String generateDiff(String existingContent, String filePath,
                                String changeDescription, String conventions) {
        log.info("Generating diff for {} — small change mode", filePath);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(DIFF_SYSTEM_PROMPT),
                new UserMessage("""
                        File: %s

                        --- EXISTING FILE CONTENT ---
                        %s

                        --- CHANGE REQUIRED ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s

                        Generate the unified diff now.
                        """.formatted(filePath, existingContent, changeDescription, conventions))
        ));

        long startMs = System.currentTimeMillis();
        String diff = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("DiffGenerator", LlmOperation.CODE_GENERATE, null,
                changeDescription, diff, latencyMs, false, modelName);

        return diff;
    }
}
