package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.ReviewPatternNodeRepository;
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

import com.assurant.brain.cache.SemanticCacheService;
import com.assurant.brain.codegen.AdaptivePromptBuilder;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.rails.OutputSchemaRail;
import com.assurant.brain.learning.SolutionPatternService;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.ContextWindowManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

@Log4j2
@Service("plannerService")
@RequiredArgsConstructor
public class PlannerService {

    private static final String EXPLAIN_SYSTEM_PROMPT = """
            You are the Project Brain — an expert software architect explaining a
            codebase to a developer who is new to the project.

            Given the retrieved context (code chunks, conventions, and graph
            relationships), produce a clear, well-structured MARKDOWN summary of
            the project. Focus on the things a new engineer needs to know to be
            productive: what the project does, how it's structured, what the key
            services are, what conventions to follow, and how the pieces fit
            together.

            Rules:
            1. Output is markdown — use headings, bullet points, and short
               paragraphs. NOT JSON.
            2. Every claim about the codebase must be grounded in the retrieved
               context. Do NOT speculate about features or files you don't see.
            3. If the context is thin, say so explicitly rather than padding the
               summary with generic Spring Boot platitudes.
            4. Cite specific class names, package names, and file paths from the
               retrieved context wherever possible.
            5. Suggested structure (adapt as needed):
               - **Overview** — one paragraph: what does this project do?
               - **Architecture** — how is it organized?
               - **Key services / components** — bullet list with one-line roles
               - **Conventions to follow** — bullet list pulled from the project's
                 documented conventions, each with its source
               - **Things to know before contributing** — gotchas, patterns,
                 anything non-obvious
            """;

    private static final String SYSTEM_PROMPT = """
            You are the Project Brain — an expert software architect generating precise, convention-aware implementation plans.

            Given a fully clarified requirement, produce a structured JSON plan. You have been provided with:
            - Relevant code context (retrieved from the project's knowledge base)
            - Project conventions (sourced from docs and code analysis)
            - Graph context (affected modules and dependencies)

            Rules:
            1. Every convention cited MUST include its source (e.g., "[source: CONTRIBUTING.md]" or "[source: inferred from 23 occurrences]").
            2. Every affected file must be a real file path from the provided context — do NOT invent paths.
            3. The "assumptions" array must always be empty — if you're unsure, state it as a risk, not an assumption.
            4. Tasks must be sequenced in safe dependency order.

            Return ONLY valid JSON matching this schema:
            {
              "requirement": "...",
              "understanding": { "why": "...", "what": "...", "where": "...", "how": "..." },
              "affectedFiles": [
                { "path": "...", "reason": "...", "confidence": 0.0-1.0 }
              ],
              "steps": [
                { "order": 1, "description": "...", "convention": "...", "files": ["..."] }
              ],
              "risks": [
                { "description": "...", "severity": "low|medium|high", "files": ["..."] }
              ],
              "conventionsApplied": [
                { "rule": "...", "source": "..." }
              ],
              "assumptions": []
            }
            """;

    private static final String CACHE_SERVICE_PLAN = "planner-plan";
    private static final String CACHE_SERVICE_EXPLAIN = "planner-explain";
    private static final String PLAN_SCHEMA = "schemas/plan.json";
    private static final String MULTI_REPO_PLAN_SCHEMA = "schemas/multi-repo-plan.json";
    private static final int MAX_NEGATIVE_KNOWLEDGE = 5;
    private static final int MAX_REVIEW_PATTERNS = 10;
    private static final int MAX_ROOT_CAUSE_CHARS = 400;
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "\\b[A-Z][A-Za-z0-9_]*(?:Service|Controller|Repository|Manager|Client|Handler|Listener|Job|Dao|Resource|Endpoint)\\b");

    private final ChatModel                  chatModel;
    private final VectorStore                vectorStore;
    private final ConventionNodeRepository   conventionNodeRepository;
    private final ProjectNodeRepository      projectNodeRepository;
    private final IncidentNodeRepository     incidentNodeRepository;
    private final ReviewPatternNodeRepository reviewPatternNodeRepository;
    private final BrainProperties            brainProperties;
    private final SemanticCacheService       semanticCacheService;
    private final TokenUsageTracker          tokenUsageTracker;
    private final AdaptivePromptBuilder      adaptivePromptBuilder;
    private final SolutionPatternService     solutionPatternService;
    private final RailChain                  railChain;
    private final ObjectMapper               objectMapper;
    private final com.assurant.brain.retrieval.HybridRetrieverService     hybridRetrieverService;
    private final com.assurant.brain.retrieval.CrossEncoderReRanker       crossEncoderReRanker;
    private final com.assurant.brain.retrieval.IterativeContextEnricher   iterativeContextEnricher;
    private final com.assurant.brain.avenger.oracle.OraclePromptOptimizer oraclePromptOptimizer;
    private final com.assurant.brain.graph.repository.CommunitySummaryNodeRepository communitySummaryNodeRepository;

    @Qualifier("brainLlmExecutor")
    private final Executor                   brainLlmExecutor;

    public String generatePlan(String projectId, String requirement,
                                List<Map<String, Object>> clarificationRounds) {
        log.info("Generating plan for project={}", projectId);

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(projectId, "PlannerService", requirement));
        String sanitizedRequirement = preLlm.sanitized();

        String cacheKey = sanitizedRequirement + "|" + clarificationRounds.size();
        Optional<String> cached = semanticCacheService.get(CACHE_SERVICE_PLAN, projectId, cacheKey);
        if (cached.isPresent()) {
            tokenUsageTracker.trackCacheHit("PlannerService", LlmOperation.PLAN, projectId, cacheKey, cached.get());
            return cached.get();
        }

        String ragContext    = retrieveRagContext(projectId, sanitizedRequirement);
        String conventions  = retrieveConventions(projectId);
        String graphContext = retrieveGraphContext(projectId, sanitizedRequirement);
        String negativeKnowledge = retrieveNegativeKnowledge(projectId, sanitizedRequirement);
        String reviewPatterns = retrieveReviewPatterns(projectId);
        String clarifications = formatClarifications(clarificationRounds);
        String adaptiveConventions = adaptivePromptBuilder.buildAdaptiveSection(projectId);
        String fewShotExamples = solutionPatternService.findFewShotExamples(projectId, sanitizedRequirement)
                .orElse("");

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        Project ID: %s

                        Requirement (fully clarified): %s

                        Clarification Q&A: %s

                        --- RELEVANT CODE CONTEXT (from RAG) ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s
                        %s

                        --- REVIEW-DERIVED CONVENTIONS (from PR comment patterns) ---
                        %s

                        --- PAST INCIDENTS — DO NOT REGRESS ---
                        %s

                        --- GRAPH CONTEXT (affected modules/classes) ---
                        %s

                        %s

                        Generate the implementation plan as JSON only.
                        """.formatted(projectId, sanitizedRequirement, clarifications,
                                      ragContext, conventions, adaptiveConventions,
                                      reviewPatterns, negativeKnowledge,
                                      graphContext, fewShotExamples))
        ));

        long startMs = System.currentTimeMillis();
        String plan = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;
        log.debug("Generated plan for project={}: {}", projectId, plan);

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("PlannerService", LlmOperation.PLAN, projectId,
                cacheKey, plan, latencyMs, false, modelName);

        Map<String, Object> postLlmMeta = new java.util.HashMap<>();
        postLlmMeta.put(OutputSchemaRail.METADATA_SCHEMA_KEY, PLAN_SCHEMA);
        postLlmMeta.put(com.assurant.brain.guardrail.rails.ContextualGroundingRail.METADATA_GROUNDING_SOURCES,
                List.of(ragContext, conventions, graphContext, negativeKnowledge, reviewPatterns));
        railChain.applyPostLlm(RailContext.postLlm(projectId, "PlannerService", plan, postLlmMeta));

        try {
            var enriched = iterativeContextEnricher.enrich(projectId, sanitizedRequirement, plan);
            if (!enriched.isEmpty()) {
                log.debug("IterativeContextEnricher pulled {} additional docs for project={}", enriched.size(), projectId);
            }
        } catch (RuntimeException e) {
            log.debug("Iterative enrichment failed: {}", e.getMessage());
        }

        int ttl = brainProperties.cache() != null ? brainProperties.cache().planTtlMinutes() : 30;
        semanticCacheService.put(CACHE_SERVICE_PLAN, projectId, cacheKey, plan, ttl);

        return plan;
    }

    public String generateMultiRepoPlan(List<String> projectIds, String requirement,
                                         List<Map<String, Object>> clarificationRounds) {
        if (projectIds == null || projectIds.isEmpty()) {
            throw new IllegalArgumentException("generateMultiRepoPlan requires at least one projectId");
        }
        log.info("Generating multi-repo plan for {} project(s): {}", projectIds.size(), projectIds);

        List<CompletableFuture<ProjectPlanResult>> futures = new ArrayList<>();
        for (String projectId : projectIds) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> safeGeneratePlan(projectId, requirement, clarificationRounds),
                    brainLlmExecutor));
        }

        List<ProjectPlanResult> results = new ArrayList<>();
        for (CompletableFuture<ProjectPlanResult> f : futures) {
            results.add(f.join());
        }

        return buildMultiRepoPlanJson(requirement, results);
    }

    private ProjectPlanResult safeGeneratePlan(String projectId, String requirement,
                                                List<Map<String, Object>> rounds) {
        try {
            return ProjectPlanResult.ok(projectId, generatePlan(projectId, requirement, rounds));
        } catch (Exception e) {
            log.warn("Per-project plan generation failed for project={} — continuing with partial umbrella plan. Cause: {}",
                    projectId, e.getMessage());
            return ProjectPlanResult.failed(projectId, e.getMessage());
        }
    }

    private String buildMultiRepoPlanJson(String requirement, List<ProjectPlanResult> results) {
        ObjectNode umbrella = objectMapper.createObjectNode();
        umbrella.put("requirement", requirement);
        umbrella.put("multiRepo",   true);

        ArrayNode projectsArr = umbrella.putArray("projects");
        for (ProjectPlanResult r : results) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("projectId", r.projectId());
            entry.put("status",    r.status());
            if (r.plan() != null) {
                try {
                    entry.set("plan", objectMapper.readTree(r.plan()));
                } catch (Exception parse) {
                    entry.put("plan_raw", r.plan());
                }
            }
            if (r.error() != null) {
                entry.put("error", r.error());
            }
            projectsArr.add(entry);
        }
        umbrella.putArray("assumptions");

        String umbrellaJson = umbrella.toString();
        long tokens = results.stream().filter(r -> r.plan() != null).count();
        String firstProjectId = results.isEmpty() ? null : results.get(0).projectId();
        tokenUsageTracker.track("PlannerService", LlmOperation.MULTI_REPO_PLAN,
                firstProjectId,
                requirement, umbrellaJson, 0L, tokens == 0,
                brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown");

        railChain.applyPostLlm(RailContext.postLlm(firstProjectId, "PlannerService", umbrellaJson,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, MULTI_REPO_PLAN_SCHEMA)));

        return umbrellaJson;
    }

    private record ProjectPlanResult(String projectId, String status, String plan, String error) {
        static ProjectPlanResult ok(String projectId, String plan) {
            return new ProjectPlanResult(projectId, "OK", plan, null);
        }
        static ProjectPlanResult failed(String projectId, String error) {
            return new ProjectPlanResult(projectId, "FAILED", null, error);
        }
    }

    public String explainProject(String projectId, String requirement) {
        log.info("Explaining project={} for requirement='{}'", projectId, requirement);

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(projectId, "PlannerService", requirement));
        String sanitizedRequirement = preLlm.sanitized();

        Optional<String> cached = semanticCacheService.get(CACHE_SERVICE_EXPLAIN, projectId, sanitizedRequirement);
        if (cached.isPresent()) {
            tokenUsageTracker.trackCacheHit("PlannerService", LlmOperation.EXPLAIN, projectId, sanitizedRequirement, cached.get());
            return cached.get();
        }

        String ragContext   = retrieveRagContext(projectId, sanitizedRequirement);
        String conventions  = retrieveConventions(projectId);
        String graphContext = retrieveGraphContext(projectId, sanitizedRequirement);
        String communityContext = retrieveCommunitySummaries(projectId);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(EXPLAIN_SYSTEM_PROMPT),
                new UserMessage("""
                        Project ID: %s

                        The developer asked: %s

                        --- COMMUNITY SUMMARIES (GraphRAG, package-level overview) ---
                        %s

                        --- RELEVANT CODE CONTEXT (from RAG) ---
                        %s

                        --- PROJECT CONVENTIONS ---
                        %s

                        --- GRAPH CONTEXT (modules / classes) ---
                        %s

                        Write the markdown summary now.
                        """.formatted(projectId, sanitizedRequirement, communityContext, ragContext, conventions, graphContext))
        ));

        long startMs = System.currentTimeMillis();
        String explanation = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;
        log.debug("Generated explanation for project={}: {}", projectId, explanation);

        String modelName = brainProperties.llm() != null ? brainProperties.llm().planModel() : "unknown";
        tokenUsageTracker.track("PlannerService", LlmOperation.EXPLAIN, projectId,
                sanitizedRequirement, explanation, latencyMs, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(projectId, "PlannerService", explanation,
                Map.of()));

        int ttl = brainProperties.cache() != null ? brainProperties.cache().planTtlMinutes() : 30;
        semanticCacheService.put(CACHE_SERVICE_EXPLAIN, projectId, sanitizedRequirement, explanation, ttl);

        return explanation;
    }

    private String retrieveRagContext(String projectId, String requirement) {
        BrainProperties.Rag rag = brainProperties.rag();
        var oracleBudget = oraclePromptOptimizer.computeBudget(projectId);
        int recallK = Math.max(oracleBudget.recallK(), rag.topKCode() + rag.topKDoc());
        int rerankK = Math.max(oracleBudget.rerankK(), rag.topKCode() + rag.topKDoc());

        var hybridResults = hybridRetrieverService.hybridSearch(projectId, requirement, recallK);
        if (hybridResults.isEmpty()) {
            return fallbackVectorRagContext(projectId, requirement, rag);
        }
        var reranked = crossEncoderReRanker.rerank(projectId, requirement, hybridResults, rerankK);
        log.debug("RAG retrieval project={} hybrid={} rerank={} (oracleTier={})",
                projectId, hybridResults.size(), reranked.size(), oracleBudget.tier());

        List<Document> codeDocs = new ArrayList<>();
        List<Document> docDocs  = new ArrayList<>();
        for (var scored : reranked) {
            Object source = scored.document().getMetadata().get("sourceType");
            if ("DOC".equalsIgnoreCase(String.valueOf(source))) docDocs.add(scored.document());
            else codeDocs.add(scored.document());
        }
        return ContextWindowManager.buildContext(codeDocs, docDocs, rag.maxContextTokens(), rag.docTrustWeight());
    }

    private String fallbackVectorRagContext(String projectId, String requirement, BrainProperties.Rag rag) {
        var b = new FilterExpressionBuilder();
        List<Document> codeDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(requirement)
                        .topK(rag.topKCode())
                        .filterExpression(b.and(
                                b.eq("projectId", projectId),
                                b.eq("sourceType", "CODE")).build())
                        .build());
        List<Document> docDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(requirement)
                        .topK(rag.topKDoc())
                        .filterExpression(b.and(
                                b.eq("projectId", projectId),
                                b.eq("sourceType", "DOC")).build())
                        .build());
        return ContextWindowManager.buildContext(codeDocs, docDocs, rag.maxContextTokens(), rag.docTrustWeight());
    }

    private String retrieveConventions(String projectId) {
        List<ConventionNode> conventions =
                conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(projectId);

        List<String> formatted = conventions.stream()
                .map(c -> "- " + c.getRule() + " [source: " + c.getSourceFile() + "]")
                .toList();

        int maxConventions = brainProperties.rag() != null ? brainProperties.rag().maxConventions() : 10;
        return ContextWindowManager.pruneConventions(formatted, maxConventions);
    }

    private String retrieveCommunitySummaries(String projectId) {
        try {
            var summaries = communitySummaryNodeRepository.findByProjectId(projectId);
            if (summaries.isEmpty()) return "(no community summaries available)";
            StringBuilder sb = new StringBuilder();
            int max = 8;
            int count = 0;
            for (var s : summaries) {
                if (count++ >= max) break;
                sb.append("- [L").append(s.getLevel()).append("] ").append(s.getCommunityKey())
                        .append(": ").append(s.getSummary()).append("\n");
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            log.debug("Community summary retrieval failed for project={}: {}", projectId, e.getMessage());
            return "(community summaries unavailable)";
        }
    }

    private String retrieveGraphContext(String projectId, String requirement) {
        String keyword = requirement.length() > 50 ? requirement.substring(0, 50) : requirement;
        List<Object> affectedClasses = projectNodeRepository.findAffectedClasses(projectId, keyword);

        int maxGraphClasses = brainProperties.rag() != null ? brainProperties.rag().maxGraphClasses() : 5;
        return ContextWindowManager.pruneGraphContext(affectedClasses, maxGraphClasses);
    }

    private String retrieveNegativeKnowledge(String projectId, String requirement) {
        Set<String> classKeywords = extractClassKeywords(requirement);
        if (classKeywords.isEmpty()) return "(no past incidents matched the requirement keywords)";

        java.util.LinkedHashMap<String, com.assurant.brain.graph.node.IncidentNode> matches = new java.util.LinkedHashMap<>();
        for (String keyword : classKeywords) {
            try {
                incidentNodeRepository.findByProjectIdAndClassHintMatch(projectId, keyword)
                        .forEach(i -> matches.putIfAbsent(i.getId(), i));
            } catch (RuntimeException e) {
                log.debug("Negative-knowledge lookup failed for keyword={}: {}", keyword, e.getMessage());
            }
            if (matches.size() >= MAX_NEGATIVE_KNOWLEDGE) break;
        }
        if (matches.isEmpty()) return "(no past incidents matched the requirement keywords)";

        StringBuilder sb = new StringBuilder();
        matches.values().stream().limit(MAX_NEGATIVE_KNOWLEDGE).forEach(incident -> {
            sb.append("- [").append(incident.getSeverity() == null ? "INCIDENT" : incident.getSeverity())
                    .append(" / ").append(incident.getOccurredAt() == null ? "" : incident.getOccurredAt())
                    .append("] ").append(incident.getTitle())
                    .append("\n  Root cause: ").append(truncate(incident.getRootCauseSummary(), MAX_ROOT_CAUSE_CHARS))
                    .append("\n  Affected classes: ")
                    .append(String.join(", ", incident.getAffectedClassHints()))
                    .append("\n");
        });
        return sb.toString().trim();
    }

    private String retrieveReviewPatterns(String projectId) {
        try {
            List<com.assurant.brain.graph.node.ReviewPatternNode> patterns =
                    reviewPatternNodeRepository.findByProjectIdAndStatusOrderByOccurrences(
                            projectId, java.util.List.of("CANDIDATE", "APPROVED"), MAX_REVIEW_PATTERNS);
            if (patterns.isEmpty()) return "(no review-derived conventions yet)";
            StringBuilder sb = new StringBuilder();
            for (com.assurant.brain.graph.node.ReviewPatternNode p : patterns) {
                sb.append("- ").append(p.getPhrase())
                        .append(" (observed ").append(p.getOccurrences())
                        .append("× across reviews)\n");
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            log.debug("Review-pattern retrieval failed for project={}: {}", projectId, e.getMessage());
            return "(no review-derived conventions yet)";
        }
    }

    private Set<String> extractClassKeywords(String requirement) {
        java.util.regex.Matcher matcher = CLASS_NAME_PATTERN.matcher(requirement);
        Set<String> keywords = new java.util.LinkedHashSet<>();
        while (matcher.find()) keywords.add(matcher.group());
        return keywords;
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    private String formatClarifications(List<Map<String, Object>> rounds) {
        if (rounds.isEmpty()) return "No clarification needed.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Map<String, Object> round = rounds.get(i);
            sb.append("Round ").append(i + 1).append(": Q=")
                    .append(round.getOrDefault("questions", "")).append(" | A=")
                    .append(round.getOrDefault("answers", "")).append("\n");
        }
        return sb.toString();
    }
}
