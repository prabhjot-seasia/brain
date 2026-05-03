package com.assurant.brain.codegen;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.graph.node.ReviewPatternNode;
import com.assurant.brain.graph.repository.ReviewPatternNodeRepository;
import com.assurant.brain.learning.AvengerMemory;
import com.assurant.brain.learning.AvengerMemorySnapshot;
import com.assurant.brain.learning.ConventionKeyExtractor;
import com.assurant.brain.util.TokenEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
@RequiredArgsConstructor
public class AdaptivePromptBuilder {

    private int violationThreshold() { return brainProperties.learning() != null ? brainProperties.learning().violationThreshold() : 3; }
    private int maxEventsToScan()    { return brainProperties.learning() != null ? brainProperties.learning().maxEventsToScan()    : 200; }

    private static final Map<String, String> ENFORCEMENT_EXAMPLES = Map.of(
            "constructor-injection",
            "CRITICAL: Use @RequiredArgsConstructor for dependency injection. NEVER use @Autowired on fields.\nCorrect:\n@RequiredArgsConstructor\npublic class MyService {\n    private final OtherService otherService;\n}",

            "log4j2-usage",
            "CRITICAL: Use @Log4j2 annotation. NEVER use @Slf4j or @Log.\nCorrect:\n@Log4j2\npublic class MyService { }",

            "no-field-injection",
            "CRITICAL: NEVER inject via @Autowired on fields. Use constructor injection with final fields + @RequiredArgsConstructor.",

            "enum-status",
            "CRITICAL: Status fields must use @Enumerated(EnumType.STRING) with a Java enum. NEVER use raw String for status.",

            "no-comments",
            "CRITICAL: Zero comments. No Javadoc, no inline //, no TODO, no FIXME. If code needs a comment, refactor it."
    );

    private static final int MAX_REVIEW_PATTERNS = 5;

    private final LearningEventRepository learningEventRepository;
    private final ConventionKeyExtractor conventionKeyExtractor;
    private final AvengerMemory avengerMemory;
    private final BrainProperties brainProperties;
    private final ReviewPatternNodeRepository reviewPatternNodeRepository;
    private final ImitationCorpusBuilder imitationCorpusBuilder;
    private final com.assurant.brain.sage.dao.ContextGapResolutionRepository contextGapResolutionRepository;

    public String buildAdaptiveSection(String projectId, AvengerType avenger) {
        AvengerMemorySnapshot snapshot = avengerMemory.getMemory(avenger, projectId);
        if (snapshot.isEmpty() || snapshot.trendHint().isBlank()) return "";

        int maxTokens = brainProperties.cache() != null && brainProperties.cache().maxMemoryHintTokens() > 0
                ? brainProperties.cache().maxMemoryHintTokens()
                : 300;

        StringBuilder section = new StringBuilder();
        section.append("\n--- ").append(avenger.name()).append(" MEMORY (prior observations on this project) ---\n");
        section.append(snapshot.trendHint());

        for (String key : snapshot.topViolations()) {
            if (TokenEstimator.estimate(section.toString()) >= maxTokens) break;
            String enforcement = ENFORCEMENT_EXAMPLES.get(key);
            if (enforcement != null) {
                section.append("\n").append(enforcement).append("\n");
            }
        }

        String result = section.toString();
        int estimated = TokenEstimator.estimate(result);
        if (estimated > maxTokens) {
            result = result.substring(0, Math.min(result.length(), maxTokens * 4));
        }

        String reviewSection = buildReviewPatternSection(projectId);
        if (!reviewSection.isEmpty()) result = result + reviewSection;

        log.info("Adaptive prompt: injected memory hints for avenger={} project={} (~{} tokens)",
                avenger, projectId, TokenEstimator.estimate(result));
        return result;
    }

    public String buildReviewPatternSection(String projectId) {
        if (projectId == null || projectId.isBlank()) return "";
        try {
            List<ReviewPatternNode> patterns = reviewPatternNodeRepository
                    .findByProjectIdAndStatusOrderByOccurrences(
                            projectId, List.of("APPROVED"), MAX_REVIEW_PATTERNS);
            if (patterns.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n--- REVIEW-DERIVED CONVENTIONS (HIGH WEIGHT — observed in repeated PR reviews) ---\n");
            for (ReviewPatternNode p : patterns) {
                sb.append("- ").append(p.getPhrase())
                        .append(" (observed ").append(p.getOccurrences()).append("× across reviews)\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.debug("Review-pattern adaptive section failed for project={}: {}", projectId, e.getMessage());
            return "";
        }
    }

    public String buildAdaptiveSection(String projectId) {
        List<LearningEvent> events = learningEventRepository.findByProjectIdOrderByCreatedAtDesc(
                projectId, PageRequest.of(0, maxEventsToScan()));

        Map<String, Integer> violationCounts = new LinkedHashMap<>();
        for (LearningEvent event : events) {
            if (event.getEventType() == LearningEventType.CONVENTION_WEIGHT_ADJUSTED
                    && event.getNewWeight() != null && event.getOldWeight() != null
                    && event.getNewWeight() < event.getOldWeight()
                    && event.getConventionRule() != null) {
                violationCounts.merge(conventionKeyExtractor.extract(event.getConventionRule()), 1, Integer::sum);
            }
        }

        List<String> weakConventions = violationCounts.entrySet().stream()
                .filter(e -> e.getValue() >= violationThreshold())
                .map(Map.Entry::getKey)
                .toList();

        if (weakConventions.isEmpty()) return "";

        StringBuilder section = new StringBuilder();
        section.append("\n--- REINFORCED CONVENTIONS (frequently violated — pay extra attention) ---\n");

        for (String convention : weakConventions) {
            String enforcement = ENFORCEMENT_EXAMPLES.get(convention);
            if (enforcement != null) {
                section.append(enforcement).append("\n\n");
            } else {
                section.append("IMPORTANT: Follow convention '").append(convention).append("' strictly.\n\n");
            }
        }

        log.info("Adaptive prompt: reinforcing {} weak conventions for project={}", weakConventions.size(), projectId);
        section.append(imitationCorpusBuilder.renderForPrompt(
                imitationCorpusBuilder.findExamples(projectId, ImitationCorpusBuilder.TaskType.GENERIC)));
        section.append(buildSageResolvedSection(projectId));
        return section.toString();
    }

    private String buildSageResolvedSection(String projectId) {
        try {
            List<com.assurant.brain.sage.domain.ContextGapResolution> resolved = contextGapResolutionRepository
                    .findByProjectIdAndStatusOrderByCreatedAtAsc(projectId,
                            com.assurant.brain.sage.GapStatus.RESOLVED);
            List<com.assurant.brain.sage.domain.ContextGapResolution> autoResolved = contextGapResolutionRepository
                    .findByProjectIdAndStatusOrderByCreatedAtAsc(projectId,
                            com.assurant.brain.sage.GapStatus.AUTO_RESOLVED);
            if (resolved.isEmpty() && autoResolved.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("\n\n--- RESOLVED PROJECT QUESTIONS (SAGE) ---\n");
            int max = Math.min(20, resolved.size() + autoResolved.size());
            int count = 0;
            for (var g : resolved) {
                if (count >= max) break;
                sb.append("- ").append(g.getGapType()).append(": ").append(g.getAnswerValue()).append('\n');
                count++;
            }
            for (var g : autoResolved) {
                if (count >= max) break;
                sb.append("- ").append(g.getGapType()).append(": ").append(g.getAnswerValue())
                        .append(" (auto)\n");
                count++;
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.debug("SAGE resolved-gap section unavailable for project={}: {}", projectId, e.getMessage());
            return "";
        }
    }

    public String buildImitationSection(String projectId, ImitationCorpusBuilder.TaskType taskType) {
        return imitationCorpusBuilder.renderForPrompt(imitationCorpusBuilder.findExamples(projectId, taskType));
    }
}
