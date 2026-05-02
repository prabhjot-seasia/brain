package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.ContextWindowManager;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class CodeGeneratorService {

    private static final String SYSTEM_PROMPT = """
            You are an expert software engineer generating production-ready code.

            You will receive:
            - A structured implementation plan (JSON with affected files, steps, conventions)
            - Existing code context from the project (retrieved via RAG)
            - Project conventions to follow strictly

            Your task: generate the COMPLETE file content for each affected file listed in the plan.

            Rules:
            1. Generate REAL, compilable, production-ready code — not stubs or placeholders
            2. Follow every convention from the plan exactly
            3. Match the existing code style from the provided context
            4. Include all necessary imports
            5. No comments, no TODOs, no FIXMEs — code must be self-documenting
            6. Handle errors properly — every catch block must recover or throw with a user-actionable message
            7. Use constructor injection (@RequiredArgsConstructor for Java), never field injection
            8. No hardcoded values — use config properties, enums, or constants

            Return a JSON object where each key is a file path and each value is the complete file content:
            {
              "src/main/java/com/example/MyService.java": "package com.example;\\n...",
              "src/main/java/com/example/MyController.java": "package com.example;\\n..."
            }

            Return ONLY valid JSON — no markdown fences, no explanation text.
            """;

    private static final int SYMBOL_DICTIONARY_MAX_CHARS = 4000;

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final ConventionNodeRepository conventionNodeRepository;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private final AdaptivePromptBuilder adaptivePromptBuilder;
    private final AiderDiffFormatter aiderDiffFormatter;
    private final AiderDiffApplier aiderDiffApplier;
    private final SymbolDictionaryBuilder symbolDictionaryBuilder;

    public Map<String, String> generateCode(String projectId, String planJson, String requirement) {
        log.info("Generating code for project={}", projectId);

        String codeContext = retrieveCodeContext(projectId, requirement);
        String conventions = retrieveConventions(projectId);
        String adaptiveConventions = adaptivePromptBuilder.buildAdaptiveSection(projectId);
        String symbolDictionary = symbolDictionaryBuilder.build(projectId)
                .renderForPrompt(SYMBOL_DICTIONARY_MAX_CHARS);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        Project ID: %s

                        --- IMPLEMENTATION PLAN ---
                        %s

                        --- EXISTING CODE CONTEXT (from RAG) ---
                        %s

                        --- PROJECT SYMBOL DICTIONARY (anti-hallucination) ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s
                        %s

                        Generate the complete file contents as JSON now.
                        """.formatted(projectId, planJson, codeContext, symbolDictionary,
                                conventions, adaptiveConventions))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("CodeGeneratorService", LlmOperation.CODE_GENERATE, projectId,
                planJson, rawResponse, latencyMs, false, modelName);

        return parseGeneratedFiles(rawResponse);
    }

    public Map<String, String> generateAiderDiffs(String projectId, String filePath, String existingContent,
                                                    String changeDescription) {
        log.info("Generating Aider SEARCH/REPLACE diffs for project={} file={}", projectId, filePath);
        String conventions = retrieveConventions(projectId);
        String symbolDictionary = symbolDictionaryBuilder.build(projectId)
                .renderForPrompt(SYMBOL_DICTIONARY_MAX_CHARS);
        String groundedConventions = conventions
                + "\n\n--- PROJECT SYMBOL DICTIONARY (anti-hallucination) ---\n"
                + symbolDictionary;
        String llmOutput = aiderDiffFormatter.generateBlocks(existingContent, filePath,
                changeDescription, groundedConventions);
        var blocks = aiderDiffApplier.parse(llmOutput);
        var result = aiderDiffApplier.apply(java.util.Map.of(filePath, existingContent == null ? "" : existingContent),
                blocks);
        if (!result.ok()) {
            log.warn("Aider diff apply produced errors for project={} file={}: {}", projectId, filePath, result.errors());
        }
        return result.updatedFiles();
    }

    public Map<String, String> fixCode(Map<String, String> currentFiles, List<String> issues, String planJson) {
        log.info("Fixing {} files based on {} review issues", currentFiles.size(), issues.size());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        The following code was generated but failed self-review.

                        --- IMPLEMENTATION PLAN ---
                        %s

                        --- CURRENT FILES ---
                        %s

                        --- ISSUES FOUND ---
                        %s

                        Fix ALL issues and return the corrected files as JSON (same format: file path → complete content).
                        Only include files that changed.
                        """.formatted(planJson, formatFiles(currentFiles), String.join("\n- ", issues)))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("CodeGeneratorService", LlmOperation.CODE_FIX, null,
                planJson, rawResponse, latencyMs, false, modelName);

        Map<String, String> fixes = parseGeneratedFiles(rawResponse);

        Map<String, String> merged = new HashMap<>(currentFiles);
        merged.putAll(fixes);
        return merged;
    }

    private String retrieveCodeContext(String projectId, String requirement) {
        var b = new FilterExpressionBuilder();
        BrainProperties.Rag rag = brainProperties.rag();

        List<Document> codeDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(requirement)
                        .topK(rag.topKCode())
                        .filterExpression(b.and(
                                b.eq("projectId", projectId),
                                b.eq("sourceType", "CODE")).build())
                        .build());

        return ContextWindowManager.buildContext(codeDocs, List.of(), rag.maxContextTokens(), rag.docTrustWeight());
    }

    private String retrieveConventions(String projectId) {
        List<String> formatted = conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(projectId).stream()
                .map(c -> "- " + c.getRule() + " [source: " + c.getSourceFile() + "]")
                .toList();

        int maxConventions = brainProperties.rag() != null ? brainProperties.rag().maxConventions() : 10;
        return ContextWindowManager.pruneConventions(formatted, maxConventions);
    }

    private String formatFiles(Map<String, String> files) {
        return files.entrySet().stream()
                .map(e -> "=== " + e.getKey() + " ===\n" + e.getValue())
                .collect(Collectors.joining("\n\n"));
    }

    private Map<String, String> parseGeneratedFiles(String rawJson) {
        String json = LlmJsonParser.stripFences(rawJson);

        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse generated code from LLM response ({} chars): {}", json.length(), e.getMessage());
            throw new IllegalStateException("Failed to parse LLM-generated code. The model returned invalid JSON.");
        }
    }
}
