package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConventionLearner")
class ConventionLearnerTest {

    private ConventionNodeRepository conventionNodeRepository;
    private LearningEventRepository learningEventRepository;
    private ConventionLearner learner;

    @BeforeEach
    void setup() {
        conventionNodeRepository = mock(ConventionNodeRepository.class);
        learningEventRepository = mock(LearningEventRepository.class);

        var ci = new BrainProperties.Ci("secret", 3, 0.1, 0.1, 3.0, 15000);
        var props = new BrainProperties(null, null, null, null, null, null, null, ci, null, null, null, null, null, null, null, null, null, null, null, null);

        learner = new ConventionLearner(conventionNodeRepository, learningEventRepository, props);
    }

    @Test
    @DisplayName("adjustWeights increases weight for followed conventions")
    void adjustWeightsFollowed() {
        ConventionNode conv = buildConvention("Use constructor injection", 1.0);
        when(conventionNodeRepository.findByProjectId("proj-1")).thenReturn(List.of(conv));
        when(learningEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LearningEvent> events = learner.adjustWeights(
                "proj-1", UUID.randomUUID(),
                List.of("constructor injection"), List.of());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getNewWeight()).isEqualTo(1.1);
        assertThat(events.get(0).getEventType()).isEqualTo(LearningEventType.CONVENTION_WEIGHT_ADJUSTED);
        verify(conventionNodeRepository).save(argThat(c -> c.getTrustWeight() == 1.1));
    }

    @Test
    @DisplayName("adjustWeights decreases weight for violated conventions")
    void adjustWeightsViolated() {
        ConventionNode conv = buildConvention("Use enum for status", 1.5);
        when(conventionNodeRepository.findByProjectId("proj-1")).thenReturn(List.of(conv));
        when(learningEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LearningEvent> events = learner.adjustWeights(
                "proj-1", UUID.randomUUID(),
                List.of(), List.of("enum for status"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getNewWeight()).isEqualTo(1.4);
        verify(conventionNodeRepository).save(argThat(c -> c.getTrustWeight() == 1.4));
    }

    @Test
    @DisplayName("adjustWeights respects max weight boundary")
    void adjustWeightsMaxBound() {
        ConventionNode conv = buildConvention("Max weight test", 2.95);
        when(conventionNodeRepository.findByProjectId("proj-1")).thenReturn(List.of(conv));
        when(learningEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LearningEvent> events = learner.adjustWeights(
                "proj-1", UUID.randomUUID(),
                List.of("max weight"), List.of());

        assertThat(events.get(0).getNewWeight()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("adjustWeights respects min weight boundary")
    void adjustWeightsMinBound() {
        ConventionNode conv = buildConvention("Min weight test", 0.15);
        when(conventionNodeRepository.findByProjectId("proj-1")).thenReturn(List.of(conv));
        when(learningEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LearningEvent> events = learner.adjustWeights(
                "proj-1", UUID.randomUUID(),
                List.of(), List.of("min weight"));

        assertThat(events.get(0).getNewWeight()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("adjustWeights skips conventions not mentioned in either list")
    void adjustWeightsUnmatched() {
        ConventionNode conv = buildConvention("Unrelated convention", 1.0);
        when(conventionNodeRepository.findByProjectId("proj-1")).thenReturn(List.of(conv));
        when(learningEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<LearningEvent> events = learner.adjustWeights(
                "proj-1", UUID.randomUUID(),
                List.of("something else"), List.of("another thing"));

        assertThat(events).isEmpty();
        verify(conventionNodeRepository, never()).save(any());
    }

    private ConventionNode buildConvention(String rule, double weight) {
        ConventionNode conv = new ConventionNode();
        conv.setId(1L);
        conv.setProjectId("proj-1");
        conv.setRule(rule);
        conv.setTrustWeight(weight);
        conv.setSourceFile("CONTRIBUTING.md");
        return conv;
    }
}
