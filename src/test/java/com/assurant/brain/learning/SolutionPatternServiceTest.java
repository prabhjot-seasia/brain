package com.assurant.brain.learning;

import com.assurant.brain.dao.SolutionPatternRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.domain.SolutionPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SolutionPatternService")
class SolutionPatternServiceTest {

    @Mock
    SolutionPatternRepository patternRepository;

    @Mock
    RedisTemplate<String, String> redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    private SolutionPatternService service;

    @BeforeEach
    void setup() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new SolutionPatternService(patternRepository, redisTemplate, props);
    }

    @Test
    @DisplayName("recordPattern saves a new SolutionPattern derived from session")
    void recordPatternSavesEntity() {
        PullRequestRecord pr = new PullRequestRecord();
        pr.setGeneratedFiles(Map.of("Foo.java", "public class Foo {}"));

        ClarificationSession session = new ClarificationSession();
        session.setProjectId("proj-1");
        session.setRequirement("Add a Foo service");

        SolutionPattern saved = new SolutionPattern();
        saved.setId(UUID.randomUUID());
        saved.setProjectId("proj-1");
        saved.setRequirementSummary("Add a Foo service");
        when(patternRepository.save(any())).thenReturn(saved);

        service.recordPattern(pr, session);

        verify(patternRepository).save(any(SolutionPattern.class));
    }

    @Test
    @DisplayName("findFewShotExamples returns empty when no patterns exist")
    void findFewShotEmptyWhenNoPatterns() {
        when(patternRepository.findByProjectIdOrderBySuccessCountDesc("proj-1")).thenReturn(List.of());
        Optional<String> result = service.findFewShotExamples("proj-1", "Add authentication");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findFewShotExamples returns empty when no overlap with requirement")
    void findFewShotEmptyWhenNoOverlap() {
        SolutionPattern pattern = new SolutionPattern();
        pattern.setRequirementSummary("Database migration script");
        pattern.setPlanSummary("Run Liquibase");

        when(patternRepository.findByProjectIdOrderBySuccessCountDesc("proj-1")).thenReturn(List.of(pattern));

        Optional<String> result = service.findFewShotExamples("proj-1", "Add authentication endpoint");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findFewShotExamples returns examples when requirement overlaps")
    void findFewShotReturnsExamplesOnOverlap() {
        SolutionPattern pattern = new SolutionPattern();
        pattern.setRequirementSummary("Add authentication service class");
        pattern.setPlanSummary("Create AuthService with JWT");
        pattern.setCodePatterns(Map.of("AuthService.java", "public class AuthService {}"));

        when(patternRepository.findByProjectIdOrderBySuccessCountDesc("proj-1")).thenReturn(List.of(pattern));

        Optional<String> result = service.findFewShotExamples("proj-1", "Add authentication service endpoint");
        assertThat(result).isPresent();
        assertThat(result.get()).contains("SIMILAR PAST SOLUTIONS");
        assertThat(result.get()).contains("Add authentication service class");
    }

    @Test
    @DisplayName("incrementSuccess increments successCount on matching pattern")
    void incrementSuccessUpdatesCount() {
        SolutionPattern pattern = new SolutionPattern();
        pattern.setId(UUID.randomUUID());
        pattern.setProjectId("proj-1");
        pattern.setRequirementSummary("Add authentication service endpoint");
        pattern.setSuccessCount(1);

        when(patternRepository.findByProjectIdOrderBySuccessCountDesc("proj-1")).thenReturn(List.of(pattern));
        when(patternRepository.save(any())).thenReturn(pattern);

        service.incrementSuccess("proj-1", "Add authentication service endpoint");

        verify(patternRepository).save(eq(pattern));
        assertThat(pattern.getSuccessCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("incrementSuccess does nothing when no matching pattern")
    void incrementSuccessNoMatchDoesNothing() {
        when(patternRepository.findByProjectIdOrderBySuccessCountDesc("proj-1")).thenReturn(List.of());
        service.incrementSuccess("proj-1", "unrelated requirement");
    }

    @Test
    @DisplayName("recordPattern handles Redis cache failure gracefully")
    void recordPatternCacheFailureDoesNotPropagate() {
        PullRequestRecord pr = new PullRequestRecord();
        pr.setGeneratedFiles(Map.of());

        ClarificationSession session = new ClarificationSession();
        session.setProjectId("proj-1");
        session.setRequirement("Add caching layer");

        SolutionPattern saved = new SolutionPattern();
        saved.setId(UUID.randomUUID());
        saved.setProjectId("proj-1");
        saved.setRequirementSummary("Add caching layer");
        when(patternRepository.save(any())).thenReturn(saved);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        service.recordPattern(pr, session);

        verify(patternRepository).save(any());
    }
}
