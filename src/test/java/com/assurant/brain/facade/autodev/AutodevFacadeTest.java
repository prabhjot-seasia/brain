package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.dto.request.AutodevCreatePrsRequest;
import com.assurant.brain.dto.response.AutodevExecuteResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.enums.BatchStatus;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.intake.IntakeResolver;
import com.assurant.brain.service.AffectedProject;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectAffinityDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AutodevFacade")
class AutodevFacadeTest {

    private IntakeResolver intakeResolver;
    private ProjectAffinityDetector affinityDetector;
    private ClarifierService clarifierService;
    private PlannerService plannerService;
    private EditOrchestrator editOrchestrator;
    private MultiRepoPrOrchestrator multiRepoPrOrchestrator;
    private ClarificationSessionRepository sessionRepository;
    private AutodevFacade facade;

    @BeforeEach
    void setup() {
        intakeResolver = mock(IntakeResolver.class);
        affinityDetector = mock(ProjectAffinityDetector.class);
        clarifierService = mock(ClarifierService.class);
        plannerService = mock(PlannerService.class);
        editOrchestrator = mock(EditOrchestrator.class);
        multiRepoPrOrchestrator = mock(MultiRepoPrOrchestrator.class);
        sessionRepository = mock(ClarificationSessionRepository.class);

        when(sessionRepository.save(any())).thenAnswer(inv -> {
            ClarificationSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        ObjectMapper om = new ObjectMapper();
        PlanGraphSerializer serializer = new PlanGraphSerializer(om);

        facade = new AutodevFacade(intakeResolver, affinityDetector, clarifierService,
                plannerService, editOrchestrator, multiRepoPrOrchestrator,
                sessionRepository, serializer, om,
                org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("start with raw payload persists session + runs affinity detection")
    void startWithPayload() {
        when(affinityDetector.detect(anyString())).thenReturn(List.of(
                new AffectedProject("backend", 0.9, "matches")));
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(ClarificationResponse.fromRawJson("""
                        {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                        """));

        AutodevSessionResponse result = facade.start("FREE_FORM", "Add auth", null, null);

        assertThat(result.planReady()).isTrue();
        assertThat(result.proposedAffectedProjects()).hasSize(1);
        verify(intakeResolver, never()).resolve(any());
    }

    @Test
    @DisplayName("start with intakeId resolves through IntakeResolver")
    void startWithIntakeId() {
        UUID intakeId = UUID.randomUUID();
        when(intakeResolver.resolve(intakeId)).thenReturn("from jira");
        when(affinityDetector.detect(anyString())).thenReturn(List.of());
        when(clarifierService.analyze(anyString(), anyString(), anyList()))
                .thenReturn(ClarificationResponse.fromRawJson("""
                        {"confident":true,"dimensions":{"why":{"score":0.9,"summary":"ok"},"what":{"score":0.9,"summary":"ok"},"where":{"score":0.9,"summary":"ok"},"how":{"score":0.9,"summary":"ok"}},"unknownReferences":[],"questions":[]}
                        """));

        AutodevSessionResponse result = facade.start("JIRA", null, intakeId.toString(), "backend");

        assertThat(result.intakeText()).isEqualTo("from jira");
        verify(intakeResolver).resolve(intakeId);
    }

    @Test
    @DisplayName("start rejects when neither payload nor intakeId is provided")
    void startRejectsEmpty() {
        assertThatThrownBy(() -> facade.start("FREE_FORM", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("plan stores umbrella plan in session.finalPlan and marks PLANNED")
    void planStoresUmbrella() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setProjectId("backend");
        session.setRequirement("req");
        session.setStatus(SessionStatus.CLARIFYING);
        session.setRounds(List.of());
        session.setAffectedProjects(List.of(Map.of("projectId", "backend", "confidence", 0.9, "rationale", "r")));
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));
        when(plannerService.generateMultiRepoPlan(anyList(), anyString(), anyList()))
                .thenReturn("{\"multiRepo\":true,\"projects\":[{\"projectId\":\"backend\",\"status\":\"OK\"}]}");

        facade.plan(sid.toString());

        assertThat(session.getStatus()).isEqualTo(SessionStatus.PLANNED);
        assertThat(session.getFinalPlan()).containsKey("projects");
    }

    @Test
    @DisplayName("execute extracts per-project seeds from umbrella plan and invokes EditOrchestrator")
    void executeExtractsSeeds() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setProjectId("backend");
        session.setRequirement("req");
        session.setStatus(SessionStatus.PLANNED);
        session.setRounds(List.of());
        session.setAffectedProjects(List.of(Map.of("projectId", "backend", "confidence", 0.9, "rationale", "r")));
        session.setFinalPlan(Map.of("projects", List.of(Map.of(
                "projectId", "backend",
                "status", "OK",
                "plan", Map.of(
                        "affectedFiles", List.of(Map.of("path", "src/Foo.java", "reason", "edit Foo")),
                        "steps", List.of()
                )
        ))));
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));
        when(editOrchestrator.orchestrate(eq("backend"), anyString(), argThat(seeds -> seeds.size() == 1
                && "src/Foo.java".equals(seeds.get(0).filePath()))))
                .thenReturn(new EditOrchestrationResult("backend", PlanGraph.empty(),
                        Map.of("src/Foo.java", "new content"), List.of()));

        AutodevExecuteResponse result = facade.execute(sid.toString(), null);

        assertThat(result.perProject()).hasSize(1);
        verify(editOrchestrator).orchestrate(eq("backend"), anyString(), argThat(seeds ->
                seeds.size() == 1 && "src/Foo.java".equals(seeds.get(0).filePath())));
        assertThat(session.getPlanGraphJson()).containsKey("generatedFiles");
    }

    @Test
    @DisplayName("createPrs threads generated files per project into MultiRepoPrOrchestrator")
    void createPrsFeedsFiles() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setProjectId("backend");
        session.setRequirement("req");
        session.setStatus(SessionStatus.EXECUTED);
        session.setRounds(List.of());
        Map<String, Object> planGraphJson = Map.of(
                "graphs", Map.of(),
                "generatedFiles", Map.of(
                        "backend", Map.of("src/Foo.java", "new")
                )
        );
        session.setPlanGraphJson(planGraphJson);
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));
        when(multiRepoPrOrchestrator.createBatch(any(), anyList(), anyString()))
                .thenReturn(new MultiRepoPrResult(UUID.randomUUID(), BatchStatus.COMPLETED, 1, 1, 0, 0, List.of()));

        facade.createPrs(sid.toString(), List.of(
                new AutodevCreatePrsRequest.RepoSpec("backend", "https://github.com/o/backend", "main")));

        verify(multiRepoPrOrchestrator).createBatch(eq(sid), argThat(targets ->
                targets.size() == 1
                        && targets.get(0).files().containsKey("src/Foo.java")
                        && "new".equals(targets.get(0).files().get("src/Foo.java"))
        ), anyString());
    }

    @Test
    @DisplayName("createPrs rejects empty repo list")
    void createPrsRejectsEmpty() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setRequirement("req");
        session.setStatus(SessionStatus.EXECUTED);
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.createPrs(sid.toString(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("plan rejects when session is in EXECUTED status (out of order)")
    void planRejectsOutOfOrder() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setRequirement("req");
        session.setStatus(SessionStatus.EXECUTED);
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.plan(sid.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot call /plan");
    }

    @Test
    @DisplayName("execute rejects when session is still CLARIFYING (skipped plan)")
    void executeRejectsBeforePlan() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setRequirement("req");
        session.setStatus(SessionStatus.CLARIFYING);
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.execute(sid.toString(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot call /execute");
    }

    @Test
    @DisplayName("createPrs rejects when session is still PLANNED (skipped execute)")
    void createPrsRejectsBeforeExecute() {
        UUID sid = UUID.randomUUID();
        ClarificationSession session = new ClarificationSession();
        session.setId(sid);
        session.setRequirement("req");
        session.setStatus(SessionStatus.PLANNED);
        when(sessionRepository.findById(sid)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.createPrs(sid.toString(), List.of(
                new AutodevCreatePrsRequest.RepoSpec("backend", "url", "main"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot call /create-prs");
    }
}
