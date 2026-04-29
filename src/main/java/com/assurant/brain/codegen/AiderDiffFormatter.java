package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class AiderDiffFormatter {

    private static final String SYSTEM_PROMPT = """
            You are an expert software engineer producing minimal SEARCH/REPLACE blocks.

            For every change emit a block in this exact format:

            path/to/File.ext
            <<<<<<< SEARCH
            (verbatim text from the existing file — must match byte-for-byte, including whitespace)
            =======
            (replacement text — what the file should contain instead)
            >>>>>>> REPLACE

            Rules:
            1. The SEARCH section MUST appear verbatim somewhere in the existing file content (no fuzzing).
            2. Keep SEARCH sections short — 3-15 lines is ideal. Surround with just enough context to be unique.
            3. Multiple SEARCH/REPLACE blocks per file are fine; emit them sequentially.
            4. Never include line numbers, hunk headers, or markdown code fences.
            5. To create a NEW file, leave the SEARCH section empty (single blank line between markers).
            6. To delete content, leave the REPLACE section empty.
            7. Follow all project conventions from the provided list.
            8. Output ONLY SEARCH/REPLACE blocks — no narration, no commentary, no fences.
            """;

    private final ChatModel chatModel;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final RailChain railChain;

    public String generateBlocks(String existingContent, String filePath,
                                  String changeDescription, String conventions) {
        log.info("Generating Aider SEARCH/REPLACE blocks for {}", filePath);

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(null, "AiderDiffFormatter",
                        changeDescription == null ? "" : changeDescription));
        String sanitizedDescription = preLlm.sanitized();

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        File: %s

                        --- EXISTING FILE CONTENT ---
                        %s

                        --- CHANGE REQUIRED ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s

                        Emit the SEARCH/REPLACE blocks now.
                        """.formatted(filePath, existingContent, sanitizedDescription, conventions))
        ));

        long startMs = System.currentTimeMillis();
        String text = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("AiderDiffFormatter", LlmOperation.CODE_GENERATE, null,
                sanitizedDescription, text, latencyMs, false, modelName);

        return text;
    }
}
