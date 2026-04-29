package com.assurant.brain.docs;

import com.assurant.brain.cache.SemanticCacheService;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.GeneratedDocumentRepository;
import com.assurant.brain.domain.GeneratedDocument;
import com.assurant.brain.enums.DocGenerationStatus;
import com.assurant.brain.enums.DocType;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.ContextWindowManager;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class DocGeneratorService {

    private static final Map<DocType, String> SYSTEM_PROMPTS = Map.of(
            DocType.FLOW_DIAGRAM, """
                    You are a senior software architect generating a **flow diagram** in Mermaid syntax.
                    Given the retrieved code context and graph relationships, produce a Markdown document with:
                    1. A title (## heading)
                    2. A brief text description of the flow
                    3. A ```mermaid code block containing a flowchart TD (top-down) diagram
                    4. Key decision points and error paths included
                    Ground every node in the diagram on actual class/method names from the context.
                    Do NOT invent classes or methods not present in the context.
                    """,
            DocType.SEQUENCE_DIAGRAM, """
                    You are a senior software architect generating a **sequence diagram** in Mermaid syntax.
                    Given the retrieved code context, produce a Markdown document with:
                    1. A title (## heading)
                    2. A brief description of the interaction
                    3. A ```mermaid code block containing a sequenceDiagram
                    4. Include all participants (classes/services) found in the context
                    Ground every participant and message on actual code from the context.
                    """,
            DocType.ARCHITECTURE, """
                    You are a senior software architect generating an **architecture overview** document.
                    Given the retrieved code context, conventions, and graph relationships, produce Markdown with:
                    1. Overview section
                    2. Component diagram in ```mermaid (graph LR or C4)
                    3. Key components with their responsibilities
                    4. Data flow description
                    5. Technology choices and conventions
                    Ground everything on the actual codebase context provided.
                    """,
            DocType.EXPLANATION, """
                    You are a senior software engineer writing a clear **technical explanation**.
                    Given the retrieved code context, explain the topic the user asked about.
                    Use Markdown with headings, bullet points, and code snippets from the actual codebase.
                    Include a ```mermaid diagram if it helps explain the flow.
                    Be specific — cite actual class names, method names, and file paths from the context.
                    """,
            DocType.CLASS_DIAGRAM, """
                    You are a senior software architect generating a **class diagram** in Mermaid syntax.
                    Given the retrieved code context, produce a Markdown document with:
                    1. A title (## heading)
                    2. A ```mermaid code block containing a classDiagram
                    3. Include inheritance, composition, and key methods
                    Ground every class and relationship on actual code from the context.
                    """
    );

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final ConventionNodeRepository conventionNodeRepository;
    private final ProjectNodeRepository projectNodeRepository;
    private final BrainProperties brainProperties;
    private final SemanticCacheService semanticCacheService;
    private final TokenUsageTracker tokenUsageTracker;
    private final GeneratedDocumentRepository documentRepository;
    private final RailChain railChain;

    public String generate(String projectId, String userPrompt, DocType docType) {
        log.info("Generating {} for project={}, prompt='{}'", docType, projectId, userPrompt);

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(projectId, "DocGeneratorService", userPrompt == null ? "" : userPrompt));
        String sanitizedPrompt = preLlm.sanitized();

        String cacheKey = docType.name() + "|" + sanitizedPrompt;
        Optional<String> cached = semanticCacheService.get("DocGeneratorService", projectId, cacheKey);
        if (cached.isPresent()) {
            tokenUsageTracker.trackCacheHit("DocGeneratorService", LlmOperation.DOC_GENERATE, projectId, cacheKey, cached.get());
            return cached.get();
        }

        String ragContext = retrieveRagContext(projectId, sanitizedPrompt);
        String conventions = retrieveConventions(projectId);
        String graphContext = retrieveGraphContext(projectId, sanitizedPrompt);

        String systemPrompt = SYSTEM_PROMPTS.getOrDefault(docType, SYSTEM_PROMPTS.get(DocType.EXPLANATION));

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage("""
                        Project ID: %s

                        User request: %s

                        --- RELEVANT CODE CONTEXT ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s

                        --- GRAPH CONTEXT (modules/classes) ---
                        %s

                        Generate the document now.
                        """.formatted(projectId, sanitizedPrompt, ragContext, conventions, graphContext))
        ));

        long startMs = System.currentTimeMillis();
        String result = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("DocGeneratorService", LlmOperation.DOC_GENERATE, projectId,
                sanitizedPrompt, result, latencyMs, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(projectId, "DocGeneratorService", result, java.util.Map.of()));

        int ttl = brainProperties.cache() != null ? brainProperties.cache().planTtlMinutes() : 30;
        semanticCacheService.put("DocGeneratorService", projectId, cacheKey, result, ttl);

        log.info("Generated {} for project={}, {} chars", docType, projectId, result.length());
        return result;
    }

    @Async("brainLlmExecutor")
    public void generateAsync(UUID documentId) {
        GeneratedDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("Async doc generation: document {} not found", documentId);
            return;
        }

        doc.setStatus(DocGenerationStatus.GENERATING);
        documentRepository.save(doc);

        try {
            String contentMd = generate(doc.getProjectId(), doc.getPrompt(), doc.getDocType());
            doc.setContentMd(contentMd);
            doc.setStatus(DocGenerationStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Async doc generation failed for {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocGenerationStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
        }

        documentRepository.save(doc);
    }

    private String retrieveRagContext(String projectId, String query) {
        BrainProperties.Rag rag = brainProperties.rag();
        var b = new FilterExpressionBuilder();

        List<Document> codeDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(rag.topKCode())
                        .filterExpression(b.and(
                                b.eq("projectId", projectId),
                                b.eq("sourceType", "CODE")).build())
                        .build());

        List<Document> docDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(rag.topKDoc())
                        .filterExpression(b.and(
                                b.eq("projectId", projectId),
                                b.eq("sourceType", "DOC")).build())
                        .build());

        return ContextWindowManager.buildContext(codeDocs, docDocs, rag.maxContextTokens(), rag.docTrustWeight());
    }

    private String retrieveConventions(String projectId) {
        List<String> formatted = conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(projectId).stream()
                .map(c -> "- " + c.getRule() + " [source: " + c.getSourceFile() + "]")
                .toList();

        int maxConventions = brainProperties.rag() != null ? brainProperties.rag().maxConventions() : 10;
        return ContextWindowManager.pruneConventions(formatted, maxConventions);
    }

    private String retrieveGraphContext(String projectId, String query) {
        String keyword = query.length() > 50 ? query.substring(0, 50) : query;
        List<Object> classes = projectNodeRepository.findAffectedClasses(projectId, keyword);

        int maxGraphClasses = brainProperties.rag() != null ? brainProperties.rag().maxGraphClasses() : 5;
        return ContextWindowManager.pruneGraphContext(classes, maxGraphClasses);
    }
}
