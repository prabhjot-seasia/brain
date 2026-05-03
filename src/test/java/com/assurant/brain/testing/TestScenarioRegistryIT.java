package com.assurant.brain.testing;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.testing.dao.TestScenarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TestScenarioRegistry — persist + QA edits round-trip")
class TestScenarioRegistryIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired private TestScenarioRegistry registry;
    @Autowired private TestScenarioRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("persist + read returns the same scenarios")
    void persistAndRead() {
        List<TestScenario> input = List.of(
                new TestScenario(null, "Happy path returns 200", TestScenarioType.AUTOMATED,
                        TestScenarioStatus.COVERED, "ExportControllerIT.happyPath", null,
                        TestScenarioSource.BRAIN),
                new TestScenario(null, ">100k rows finishes in 30s", TestScenarioType.MANUAL,
                        TestScenarioStatus.MANUAL_REQUIRED, null, null, TestScenarioSource.BRAIN));

        registry.persist("BRAIN-1", input);
        List<TestScenario> read = registry.readForIssue("BRAIN-1");

        assertThat(read).hasSize(2);
        assertThat(read).extracting(TestScenario::description)
                .contains("Happy path returns 200", ">100k rows finishes in 30s");
        assertThat(read).extracting(TestScenario::scenarioId)
                .allMatch(id -> id.startsWith("TC-BRAIN-1-"));
    }

    @Test
    @DisplayName("QA edit (no scenarioId in submission) becomes QA-added with generated id")
    void qaAdditionMarkedAndIdGenerated() {
        registry.persist("BRAIN-2", List.of(
                new TestScenario(null, "Brain's first scenario", TestScenarioType.AUTOMATED,
                        TestScenarioStatus.COVERED, null, null, TestScenarioSource.BRAIN)));

        List<TestScenario> qaEdits = List.of(
                new TestScenario(null, "QA-added: handle null safely", TestScenarioType.MANUAL,
                        TestScenarioStatus.PENDING, null, null, null));

        registry.applyQaEdits("BRAIN-2", qaEdits);
        List<TestScenario> all = registry.readForIssue("BRAIN-2");

        assertThat(all).extracting(TestScenario::source)
                .containsExactlyInAnyOrder(TestScenarioSource.BRAIN, TestScenarioSource.QA);
        assertThat(all).filteredOn(s -> s.source() == TestScenarioSource.QA)
                .singleElement()
                .satisfies(qa -> {
                    assertThat(qa.status()).isEqualTo(TestScenarioStatus.QA_ADDED);
                    assertThat(qa.scenarioId()).startsWith("TC-BRAIN-2-");
                });
    }
}
