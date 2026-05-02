package com.assurant.brain.avenger;

import com.assurant.brain.avenger.dto.AvengerRequest;
import com.assurant.brain.avenger.dto.AvengerResponse;
import com.assurant.brain.codegen.AdaptivePromptBuilder;
import com.assurant.brain.codegen.AstValidator;
import com.assurant.brain.codegen.ConventionChecker;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.AvengerReviewRepository;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.learning.AvengerMemory;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.rails.OutputSchemaRail;
import com.assurant.brain.jobs.AsyncJobService;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class AvengerReviewer {

    private static final String AVENGER_SCHEMA = "schemas/avenger-review-result.json";
    private static final String SINGLE_FILE_KEY = "submitted.java";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final AvengerPersonaLoader personaLoader;
    private final AvengerReviewRepository repository;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final RailChain railChain;
    private final AstValidator astValidator;
    private final ConventionChecker conventionChecker;
    private final LearningEventRepository learningEventRepository;
    private final AvengerMemory avengerMemory;
    private final AdaptivePromptBuilder adaptivePromptBuilder;
    private final com.assurant.brain.graph.repository.TestRunNodeRepository testRunNodeRepository;
    private final AsyncJobService asyncJobService;
    private final MirageReviewer mirageReviewer;
    private final VectorStore vectorStore;

    private static final int MIRAGE_BASELINE_SAMPLES = 30;

    @Async("brainLlmExecutor")
    public void reviewAsync(AvengerRequest request, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId,
                    request.avenger().name() + " reviewing project " + request.projectId());
            AvengerResponse response = review(request);
            asyncJobService.markSucceeded(jobId, response);
        } catch (Exception e) {
            log.error("Avenger {} review job={} failed: {}", request.avenger(), jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public static String requestHash(AvengerType avenger, String projectId, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = avenger.name() + "|" + (projectId == null ? "_" : projectId) + "|"
                    + (code == null ? "" : code);
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8))).substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public AvengerResponse review(AvengerRequest request) {
        log.info("Avenger {} reviewing for project={}", request.avenger(), request.projectId());

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(request.projectId(), "AvengerReviewer." + request.avenger(), request.code()));
        String sanitizedCode = preLlm.sanitized();

        long startMs = System.currentTimeMillis();
        AvengerVerdict verdict;
        List<String> issues;
        String summary;
        int tokensIn = 0;
        int tokensOut = 0;

        if (request.avenger() == AvengerType.STARK) {
            StarkResult stark = reviewWithStark(sanitizedCode);
            verdict = stark.verdict;
            issues = stark.issues;
            summary = stark.summary;
        } else if (request.avenger() == AvengerType.MIRAGE) {
            MirageResult m = reviewWithMirage(request, sanitizedCode);
            verdict = m.verdict;
            issues = m.issues;
            summary = m.summary;
        } else {
            LlmReviewResult llm = reviewWithLlm(request, sanitizedCode);
            verdict = llm.verdict;
            issues = llm.issues;
            summary = llm.summary;
            tokensIn = llm.tokensIn;
            tokensOut = llm.tokensOut;
        }

        long latencyMs = System.currentTimeMillis() - startMs;

        AvengerReview saved = persist(request, verdict, issues, summary, tokensIn, tokensOut, latencyMs);

        if (verdict != AvengerVerdict.APPROVED) {
            recordLearningEvents(request, issues);
            avengerMemory.invalidate(request.avenger(), request.projectId());
        }

        return new AvengerResponse(saved.getId(), request.avenger(), verdict, issues, summary, latencyMs);
    }

    private void recordLearningEvents(AvengerRequest request, List<String> issues) {
        for (String issue : issues) {
            if (issue == null || issue.isBlank()) continue;
            LearningEvent event = new LearningEvent();
            event.setProjectId(request.projectId());
            event.setAvenger(request.avenger());
            event.setEventType(LearningEventType.AVENGER_VIOLATION_OBSERVED);
            event.setConventionRule(issue);
            learningEventRepository.save(event);
        }
    }

    private StarkResult reviewWithStark(String code) {
        Map<String, String> files = Map.of(SINGLE_FILE_KEY, code);
        AstValidator.ValidationResult ast = astValidator.validate(files);
        List<String> conventionViolations = conventionChecker.check(files);

        boolean astFail = !ast.passed();
        boolean conventionFail = !conventionViolations.isEmpty();

        List<String> combined = new java.util.ArrayList<>(ast.issues());
        combined.addAll(conventionViolations);

        if (astFail || conventionFail) {
            return new StarkResult(AvengerVerdict.CHANGES_REQUESTED, combined,
                    "STARK found " + combined.size() + " structural issue(s)");
        }
        return new StarkResult(AvengerVerdict.APPROVED, List.of(), "STARK found no structural issues");
    }

    private static final double FLAKY_THRESHOLD = 0.2;
    private static final int MAX_FLAKY_TESTS_LISTED = 10;

    private String buildAvengerSpecificContext(AvengerRequest request) {
        if (request.avenger() == AvengerType.WIDOW) {
            try {
                var flaky = testRunNodeRepository.findFlakyTests(
                        request.projectId(), FLAKY_THRESHOLD, MAX_FLAKY_TESTS_LISTED);
                if (flaky.isEmpty()) return "";
                StringBuilder sb = new StringBuilder("\n\n--- KNOWN FLAKY TESTS IN THIS PROJECT (score >= 0.2) ---\n");
                flaky.forEach(t -> sb.append("- ").append(t.getTestFqn())
                        .append(" (flakiness=").append(String.format("%.2f", t.getFlakinessScore()))
                        .append(", fail/total=").append(t.getFailCount()).append("/").append(t.getTotalRuns())
                        .append(")\n"));
                sb.append("If the submitted code touches any of these tests, recommend stabilizing them BEFORE accepting the change.");
                return sb.toString();
            } catch (RuntimeException e) {
                log.debug("WIDOW flaky-tests lookup failed for project={}: {}",
                        request.projectId(), e.getMessage());
                return "";
            }
        }
        return "";
    }

    private LlmReviewResult reviewWithLlm(AvengerRequest request, String sanitizedCode) {
        String persona = personaLoader.load(request.avenger());
        String memoryHints = adaptivePromptBuilder.buildAdaptiveSection(request.projectId(), request.avenger());
        String avengerSpecificContext = buildAvengerSpecificContext(request);
        String userPrompt = buildUserPrompt(request, sanitizedCode);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(persona + memoryHints + avengerSpecificContext +
                        "\n\nReturn ONLY valid JSON matching this structure: " +
                        "{\"verdict\":\"APPROVED|CHANGES_REQUESTED|BLOCKED\",\"issues\":[],\"summary\":\"\"}"),
                new UserMessage(userPrompt)
        ));

        long start = System.currentTimeMillis();
        String raw = chatModel.call(prompt).getResult().getOutput().getText();
        long latency = System.currentTimeMillis() - start;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("AvengerReviewer." + request.avenger(), LlmOperation.AVENGER_REVIEW,
                request.projectId(), userPrompt, raw, latency, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(request.projectId(),
                "AvengerReviewer." + request.avenger(), raw,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, AVENGER_SCHEMA)));

        return parseLlmResponse(raw);
    }

    private LlmReviewResult parseLlmResponse(String raw) {
        String json = LlmJsonParser.stripFences(raw);
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            String verdictStr = (String) parsed.getOrDefault("verdict", "CHANGES_REQUESTED");
            AvengerVerdict verdict = AvengerVerdict.valueOf(verdictStr.toUpperCase());
            @SuppressWarnings("unchecked")
            List<String> issues = (List<String>) parsed.getOrDefault("issues", List.of());
            String summary = (String) parsed.getOrDefault("summary", "");
            int inEst = raw.length() / 4;
            return new LlmReviewResult(verdict, issues, summary, inEst, raw.length() / 4);
        } catch (Exception e) {
            log.error("Failed to parse Avenger LLM response: {}", e.getMessage());
            return new LlmReviewResult(AvengerVerdict.CHANGES_REQUESTED,
                    List.of("Failed to parse LLM response: " + e.getMessage()),
                    "Parse failure", 0, 0);
        }
    }

    private String buildUserPrompt(AvengerRequest request, String code) {
        String domain = request.avenger().domain().name();
        String ctx = request.context() == null ? "" : "\n\n--- ADDITIONAL CONTEXT ---\n" + request.context();
        return """
                You are reviewing within your domain: %s.
                Project ID: %s

                --- SUBMITTED CODE/ARTIFACT ---
                %s%s

                Respond with the JSON verdict only.
                """.formatted(domain, request.projectId(), code, ctx);
    }

    private AvengerReview persist(AvengerRequest request, AvengerVerdict verdict, List<String> issues,
                                   String summary, int tokensIn, int tokensOut, long latencyMs) {
        AvengerReview review = new AvengerReview();
        review.setAvenger(request.avenger());
        review.setProjectId(request.projectId());
        review.setRequestHash(hash(request));
        review.setVerdict(verdict);
        review.setIssues(issues);
        review.setSummary(summary);
        review.setTokensIn(tokensIn);
        review.setTokensOut(tokensOut);
        review.setLatencyMs(latencyMs);
        return repository.save(review);
    }

    private String hash(AvengerRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = request.avenger().name() + "|" + request.projectId() + "|" +
                    (request.code() == null ? "" : request.code());
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private MirageResult reviewWithMirage(AvengerRequest request, String code) {
        List<String> baseline = sampleProjectSource(request.projectId());
        MirageReviewer.Report report = mirageReviewer.review(Map.of(SINGLE_FILE_KEY, code), baseline);
        AvengerVerdict v = switch (report.verdict()) {
            case APPROVED -> AvengerVerdict.APPROVED;
            case FEELS_LIKE_LLM, REWRITE_TO_MATCH_HOUSE_STYLE -> AvengerVerdict.CHANGES_REQUESTED;
        };
        String summary = report.verdict() == MirageReviewer.Verdict.APPROVED
                ? "MIRAGE: candidate matches house style"
                : "MIRAGE: " + report.signals().size() + " style mismatch(es) (max " + String.format("%.1fσ", report.maxSigmaOff()) + ")";
        return new MirageResult(v, report.signals(), summary);
    }

    private List<String> sampleProjectSource(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("class implementation")
                    .topK(MIRAGE_BASELINE_SAMPLES)
                    .filterExpression(b.and(
                            b.eq("projectId", projectId),
                            b.eq("sourceType", "CODE")).build())
                    .build());
            if (docs == null || docs.isEmpty()) return List.of();
            List<String> out = new java.util.ArrayList<>(docs.size());
            for (Document d : docs) {
                if (d.getText() != null && !d.getText().isBlank()) out.add(d.getText());
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("MIRAGE: baseline sampling failed for project={}: {}", projectId, e.getMessage());
            return List.of();
        }
    }

    private record StarkResult(AvengerVerdict verdict, List<String> issues, String summary) {}

    private record MirageResult(AvengerVerdict verdict, List<String> issues, String summary) {}

    private record LlmReviewResult(AvengerVerdict verdict, List<String> issues, String summary,
                                    int tokensIn, int tokensOut) {}
}
