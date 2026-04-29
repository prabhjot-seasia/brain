package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.SolutionPatternRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.domain.SolutionPattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class SolutionPatternService {

    private static final String PATTERN_CACHE_PREFIX = "brain:patterns:";

    private final SolutionPatternRepository patternRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final BrainProperties brainProperties;

    private int maxFewShotExamples()   { return brainProperties.learning() != null ? brainProperties.learning().maxFewShotExamples() : 3; }
    private int patternCacheTtlHours() { return brainProperties.cache() != null ? brainProperties.cache().patternCacheTtlHours()     : 24; }

    public void recordPattern(PullRequestRecord prRecord, ClarificationSession session) {
        SolutionPattern pattern = new SolutionPattern();
        pattern.setProjectId(session.getProjectId());
        pattern.setRequirementSummary(session.getRequirement());

        if (session.getFinalPlan() != null) {
            Object requirement = session.getFinalPlan().get("requirement");
            pattern.setPlanSummary(requirement != null ? requirement.toString() : session.getRequirement());
        }

        pattern.setCodePatterns(prRecord.getGeneratedFiles());
        pattern.setSuccessCount(1);
        pattern.setUsageCount(1);

        patternRepository.save(pattern);
        cachePattern(pattern);

        log.info("Recorded solution pattern for project={}, requirement='{}'",
                session.getProjectId(), truncate(session.getRequirement(), 80));
    }

    public Optional<String> findFewShotExamples(String projectId, String requirement) {
        List<SolutionPattern> patterns = patternRepository.findByProjectIdOrderBySuccessCountDesc(projectId);

        if (patterns.isEmpty()) return Optional.empty();

        String requirementLower = requirement.toLowerCase();
        List<SolutionPattern> relevant = patterns.stream()
                .filter(p -> hasOverlap(p.getRequirementSummary().toLowerCase(), requirementLower))
                .limit(maxFewShotExamples())
                .toList();

        if (relevant.isEmpty()) return Optional.empty();

        StringBuilder examples = new StringBuilder();
        examples.append("--- SIMILAR PAST SOLUTIONS (for reference) ---\n");

        for (int i = 0; i < relevant.size(); i++) {
            SolutionPattern p = relevant.get(i);
            examples.append("Example ").append(i + 1).append(": ").append(p.getRequirementSummary()).append("\n");
            examples.append("Plan: ").append(p.getPlanSummary()).append("\n");

            if (p.getCodePatterns() != null) {
                examples.append("Files: ").append(String.join(", ", p.getCodePatterns().keySet())).append("\n");
            }
            examples.append("---\n");
        }

        log.info("Found {} few-shot examples for project={}", relevant.size(), projectId);
        return Optional.of(examples.toString());
    }

    public void incrementSuccess(String projectId, String requirement) {
        List<SolutionPattern> patterns = patternRepository.findByProjectIdOrderBySuccessCountDesc(projectId);
        String requirementLower = requirement.toLowerCase();

        patterns.stream()
                .filter(p -> hasOverlap(p.getRequirementSummary().toLowerCase(), requirementLower))
                .findFirst()
                .ifPresent(p -> {
                    p.setSuccessCount(p.getSuccessCount() + 1);
                    patternRepository.save(p);
                });
    }

    private boolean hasOverlap(String a, String b) {
        String[] wordsA = a.split("\\s+");
        String[] wordsB = b.split("\\s+");
        int matches = 0;
        for (String wa : wordsA) {
            if (wa.length() < 4) continue;
            for (String wb : wordsB) {
                if (wa.equals(wb)) { matches++; break; }
            }
        }
        return matches >= 2;
    }

    private void cachePattern(SolutionPattern pattern) {
        try {
            String key = PATTERN_CACHE_PREFIX + pattern.getProjectId() + ":" + pattern.getId();
            redisTemplate.opsForValue().set(key, pattern.getRequirementSummary(),
                    patternCacheTtlHours(), TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to cache solution pattern: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
