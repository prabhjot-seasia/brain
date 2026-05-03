package com.assurant.brain.testing;

import com.assurant.brain.testing.dao.TestScenarioRepository;
import com.assurant.brain.testing.domain.TestScenarioEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class TestScenarioRegistry {

    private final TestScenarioRepository repository;

    public List<TestScenario> readForIssue(String issueKey) {
        return repository.findByIssueKeyOrderByCreatedAtAsc(issueKey).stream()
                .map(this::toScenario)
                .toList();
    }

    @Transactional
    public List<TestScenario> persist(String issueKey, List<TestScenario> scenarios) {
        if (issueKey == null || scenarios == null || scenarios.isEmpty()) return List.of();
        List<TestScenario> saved = new ArrayList<>();
        for (TestScenario s : scenarios) {
            String scenarioId = s.scenarioId() == null || s.scenarioId().isBlank()
                    ? TestScenario.idFor(issueKey, s.description())
                    : s.scenarioId();
            Optional<TestScenarioEntity> existing = repository.findByIssueKeyAndScenarioId(issueKey, scenarioId);
            TestScenarioEntity entity = existing.orElseGet(TestScenarioEntity::new);
            entity.setIssueKey(issueKey);
            entity.setScenarioId(scenarioId);
            entity.setDescription(s.description());
            entity.setType(s.type());
            entity.setStatus(s.status());
            entity.setLinkedTest(s.linkedTest());
            entity.setQaNotes(s.qaNotes());
            entity.setSource(s.source() == null ? TestScenarioSource.BRAIN : s.source());
            TestScenarioEntity persisted = repository.save(entity);
            saved.add(toScenario(persisted));
        }
        return saved;
    }

    @Transactional
    public List<TestScenario> applyQaEdits(String issueKey, List<TestScenario> qaSubmitted) {
        if (issueKey == null || qaSubmitted == null) return readForIssue(issueKey);
        List<TestScenario> mergedInputs = new ArrayList<>();
        for (TestScenario s : qaSubmitted) {
            String scenarioId = s.scenarioId() == null || s.scenarioId().isBlank()
                    ? TestScenario.idFor(issueKey, s.description())
                    : s.scenarioId();
            TestScenarioSource source = s.scenarioId() == null || s.scenarioId().isBlank()
                    ? TestScenarioSource.QA
                    : (s.source() == null ? TestScenarioSource.QA : s.source());
            TestScenarioStatus status = source == TestScenarioSource.QA
                    && (s.status() == null || s.status() == TestScenarioStatus.PENDING)
                    ? TestScenarioStatus.QA_ADDED
                    : s.status();
            mergedInputs.add(new TestScenario(scenarioId, s.description(), s.type(),
                    status, s.linkedTest(), s.qaNotes(), source));
        }
        return persist(issueKey, mergedInputs);
    }

    private TestScenario toScenario(TestScenarioEntity e) {
        return new TestScenario(e.getScenarioId(), e.getDescription(), e.getType(), e.getStatus(),
                e.getLinkedTest(), e.getQaNotes(), e.getSource());
    }
}
