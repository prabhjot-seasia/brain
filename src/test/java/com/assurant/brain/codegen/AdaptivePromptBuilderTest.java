package com.assurant.brain.codegen;

import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.LearningEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AdaptivePromptBuilder")
class AdaptivePromptBuilderTest {

    private LearningEventRepository learningEventRepository;
    private AdaptivePromptBuilder builder;

    @BeforeEach
    void setup() {
        learningEventRepository = mock(LearningEventRepository.class);
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(any(), any(), anyInt()))
                .thenReturn(List.of());
        builder = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));
    }

    @Test
    @DisplayName("returns empty when no violations")
    void noViolations() {
        when(learningEventRepository.findByProjectIdOrderByCreatedAtDesc(eq("proj-1"), any())).thenReturn(List.of());
        String result = builder.buildAdaptiveSection("proj-1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty when violations below threshold")
    void belowThreshold() {
        List<LearningEvent> events = new ArrayList<>();
        events.add(buildViolation("Use constructor injection", 1.5, 1.4));
        events.add(buildViolation("Use constructor injection", 1.4, 1.3));
        when(learningEventRepository.findByProjectIdOrderByCreatedAtDesc(eq("proj-1"), any())).thenReturn(events);

        String result = builder.buildAdaptiveSection("proj-1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("injects enforcement when convention violated 3+ times")
    void aboveThreshold() {
        List<LearningEvent> events = new ArrayList<>();
        events.add(buildViolation("Use constructor injection", 1.5, 1.4));
        events.add(buildViolation("Use constructor injection via @RequiredArgsConstructor", 1.4, 1.3));
        events.add(buildViolation("Constructor injection required", 1.3, 1.2));
        when(learningEventRepository.findByProjectIdOrderByCreatedAtDesc(eq("proj-1"), any())).thenReturn(events);

        String result = builder.buildAdaptiveSection("proj-1");
        assertThat(result).contains("CRITICAL");
        assertThat(result).contains("@RequiredArgsConstructor");
    }

    @Test
    @DisplayName("handles multiple weak conventions")
    void multipleWeakConventions() {
        List<LearningEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            events.add(buildViolation("Use constructor injection", 1.5 - i * 0.1, 1.4 - i * 0.1));
            events.add(buildViolation("Use @Log4j2 not @Slf4j", 1.5 - i * 0.1, 1.4 - i * 0.1));
        }
        when(learningEventRepository.findByProjectIdOrderByCreatedAtDesc(eq("proj-1"), any())).thenReturn(events);

        String result = builder.buildAdaptiveSection("proj-1");
        assertThat(result).contains("@RequiredArgsConstructor");
        assertThat(result).contains("@Log4j2");
    }

    private LearningEvent buildViolation(String rule, double oldWeight, double newWeight) {
        LearningEvent event = new LearningEvent();
        event.setProjectId("proj-1");
        event.setEventType(LearningEventType.CONVENTION_WEIGHT_ADJUSTED);
        event.setConventionRule(rule);
        event.setOldWeight(oldWeight);
        event.setNewWeight(newWeight);
        return event;
    }

    @Test
    @DisplayName("buildReviewPatternSection emits APPROVED patterns with phrase + occurrences")
    void reviewPatternsInjected() {
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        var p = new com.assurant.brain.graph.node.ReviewPatternNode();
        p.setProjectId("proj-1");
        p.setPhrase("Use constructor injection, not @Autowired fields");
        p.setOccurrences(7);
        p.setStatus("APPROVED");
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(eq("proj-1"), any(), anyInt()))
                .thenReturn(List.of(p));

        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var builder = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        String section = builder.buildReviewPatternSection("proj-1");

        assertThat(section).contains("REVIEW-DERIVED CONVENTIONS")
                .contains("Use constructor injection, not @Autowired fields")
                .contains("7×");
    }

    @Test
    @DisplayName("buildReviewPatternSection returns empty when no APPROVED patterns")
    void reviewPatternsEmpty() {
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(any(), any(), anyInt()))
                .thenReturn(List.of());

        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var builder = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        assertThat(builder.buildReviewPatternSection("proj-1")).isEmpty();
    }

    @Test
    @DisplayName("buildAdaptiveSection(projectId, avenger): empty memory snapshot returns empty")
    void avengerMemorySnapshotEmptyReturnsEmpty() {
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        when(memory.getMemory(any(), any())).thenReturn(
                com.assurant.brain.learning.AvengerMemorySnapshot.empty(
                        com.assurant.brain.enums.AvengerType.HAWKEYE, "proj-1"));
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var b = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        assertThat(b.buildAdaptiveSection("proj-1", com.assurant.brain.enums.AvengerType.HAWKEYE)).isEmpty();
    }

    @Test
    @DisplayName("buildAdaptiveSection(projectId, avenger): populated snapshot includes trend hint")
    void avengerMemoryPopulatedIncludesTrendHint() {
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var snap = new com.assurant.brain.learning.AvengerMemorySnapshot(
                com.assurant.brain.enums.AvengerType.STARK, "proj-1", 10,
                java.util.Map.of(),
                List.of("constructor-injection"),
                List.of(),
                "trend: bean injection");
        when(memory.getMemory(any(), any())).thenReturn(snap);
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(any(), any(), anyInt()))
                .thenReturn(List.of());
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var b = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        String section = b.buildAdaptiveSection("proj-1", com.assurant.brain.enums.AvengerType.STARK);
        assertThat(section).contains("STARK MEMORY")
                .contains("trend: bean injection")
                .contains("@RequiredArgsConstructor");
    }

    @Test
    @DisplayName("buildAdaptiveSection(projectId, avenger): blank trendHint returns empty even with totalEvents > 0")
    void blankTrendHintReturnsEmpty() {
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var snap = new com.assurant.brain.learning.AvengerMemorySnapshot(
                com.assurant.brain.enums.AvengerType.STARK, "proj-1", 5,
                java.util.Map.of(), List.of(), List.of(), "");
        when(memory.getMemory(any(), any())).thenReturn(snap);
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var b = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        assertThat(b.buildAdaptiveSection("proj-1", com.assurant.brain.enums.AvengerType.STARK)).isEmpty();
    }

    @Test
    @DisplayName("buildReviewPatternSection: blank/null projectId returns empty")
    void blankProjectIdReturnsEmpty() {
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var b = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        assertThat(b.buildReviewPatternSection(null)).isEmpty();
        assertThat(b.buildReviewPatternSection("")).isEmpty();
    }

    @Test
    @DisplayName("buildReviewPatternSection: repo throwing RuntimeException returns empty")
    void reviewRepoExceptionReturnsEmpty() {
        var reviewRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("graph offline"));
        var cache = new com.assurant.brain.config.properties.BrainProperties.Cache(0.90, 30, 5, true, 24, 300, 24);
        var props = new com.assurant.brain.config.properties.BrainProperties(null, null, null, null, null, null, null, null, cache, null, null, null, null, null, null, null, null, null, null);
        var extractor = new com.assurant.brain.learning.ConventionKeyExtractor(props);
        var memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        var b = new AdaptivePromptBuilder(learningEventRepository, extractor, memory, props, reviewRepo,
                new ImitationCorpusBuilder(mock(org.springframework.ai.vectorstore.VectorStore.class)));

        assertThat(b.buildReviewPatternSection("proj-1")).isEmpty();
    }
}
