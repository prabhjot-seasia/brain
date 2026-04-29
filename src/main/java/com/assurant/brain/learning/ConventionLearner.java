package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.enums.LearningEventType;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class ConventionLearner {

    private final ConventionNodeRepository conventionNodeRepository;
    private final LearningEventRepository learningEventRepository;
    private final BrainProperties brainProperties;

    @Transactional
    public List<LearningEvent> adjustWeights(String projectId, UUID prRecordId,
                                              List<String> followedConventions,
                                              List<String> violatedConventions) {
        List<ConventionNode> conventions = conventionNodeRepository.findByProjectId(projectId);
        List<LearningEvent> events = new ArrayList<>();

        double delta = brainProperties.ci().learningWeightDelta();
        double minWeight = brainProperties.ci().minTrustWeight();
        double maxWeight = brainProperties.ci().maxTrustWeight();

        for (ConventionNode convention : conventions) {
            boolean wasFollowed = followedConventions.stream()
                    .anyMatch(f -> convention.getRule().toLowerCase().contains(f.toLowerCase()));
            boolean wasViolated = violatedConventions.stream()
                    .anyMatch(v -> convention.getRule().toLowerCase().contains(v.toLowerCase()));

            if (wasFollowed && !wasViolated) {
                double oldWeight = convention.getTrustWeight();
                double newWeight = Math.min(oldWeight + delta, maxWeight);
                convention.setTrustWeight(newWeight);
                conventionNodeRepository.save(convention);

                events.add(createEvent(projectId, prRecordId, LearningEventType.CONVENTION_WEIGHT_ADJUSTED,
                        convention.getRule(), oldWeight, newWeight, "Convention followed in merged PR"));

            } else if (wasViolated) {
                double oldWeight = convention.getTrustWeight();
                double newWeight = Math.max(oldWeight - delta, minWeight);
                convention.setTrustWeight(newWeight);
                conventionNodeRepository.save(convention);

                events.add(createEvent(projectId, prRecordId, LearningEventType.CONVENTION_WEIGHT_ADJUSTED,
                        convention.getRule(), oldWeight, newWeight, "Convention violated — developer overrode in merged PR"));
            }
        }

        log.info("Adjusted {} convention weights for project={}", events.size(), projectId);
        return learningEventRepository.saveAll(events);
    }

    private LearningEvent createEvent(String projectId, UUID prRecordId, LearningEventType type,
                                       String rule, double oldWeight, double newWeight, String reason) {
        LearningEvent event = new LearningEvent();
        event.setProjectId(projectId);
        event.setPrRecordId(prRecordId);
        event.setEventType(type);
        event.setConventionRule(rule);
        event.setOldWeight(oldWeight);
        event.setNewWeight(newWeight);
        event.setDetails(Map.of("reason", reason, "delta", newWeight - oldWeight));
        return event;
    }
}
