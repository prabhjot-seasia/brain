package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.Project;
import com.assurant.brain.dto.response.AutodevPlanResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.dto.response.ClarificationResponse.ClarificationQuestion;
import com.assurant.brain.enums.BatchStatus;
import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.facade.autodev.AutodevFacade;
import com.assurant.brain.facade.autodev.MultiRepoPrResult;
import com.assurant.brain.facade.autodev.PerRepoResult;
import com.assurant.brain.jira.dao.JiraIssueRunRepository;
import com.assurant.brain.jira.domain.JiraIssueRun;
import com.assurant.brain.jira.enums.JiraRunState;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.service.AffectedProject;
import com.assurant.brain.service.ProjectAffinityDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JiraDrivenAutodevOrchestrator state machine")
@SuppressWarnings("unchecked")
class JiraDrivenAutodevOrchestratorTest {

    private JiraClient jiraClient;
    private JiraIssueMapper jiraIssueMapper;
    private JiraCommentFormatter formatter;
    private JiraIssueRunRepository runRepo;
    private ClarificationSessionRepository sessionRepo;
    private ProjectRepository projectRepo;
    private ProjectAffinityDetector affinityDetector;
    private AutodevFacade autodevFacade;
    private AsyncJobService asyncJobService;
    private com.assurant.brain.guardrail.RailChain railChain;
    private JiraDrivenAutodevOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        jiraClient = mock(JiraClient.class);
        jiraIssueMapper = mock(JiraIssueMapper.class);
        formatter = mock(JiraCommentFormatter.class);
        runRepo = mock(JiraIssueRunRepository.class);
        sessionRepo = mock(ClarificationSessionRepository.class);
        projectRepo = mock(ProjectRepository.class);
        affinityDetector = mock(ProjectAffinityDetector.class);
        autodevFacade = mock(AutodevFacade.class);
        asyncJobService = mock(AsyncJobService.class);
        railChain = mock(com.assurant.brain.guardrail.RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(inv -> {
            com.assurant.brain.guardrail.RailContext ctx = inv.getArgument(0);
            return new com.assurant.brain.guardrail.RailChain.ChainResult(ctx, java.util.List.of());
        });
        when(runRepo.save(any(JiraIssueRun.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainProperties.Jira jira = new BrainProperties.Jira(
                null, null, null, null, "secret", "AI_DEV_", "bot");
        BrainProperties props = new BrainProperties(null, null, null, null, null, jira, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        when(jiraClient.getIssueWithComments(eq("bot"), anyString())).thenReturn(Map.of(
                "key", "BRAIN-1",
                "fields", Map.of("summary", "Add CSV export", "description", "Need a /export endpoint")));
        when(jiraIssueMapper.extractIssueKey(any())).thenReturn("BRAIN-1");
        when(jiraIssueMapper.extractSummary(any())).thenReturn("Add CSV export");
        when(formatter.questions(anyList(), anyString())).thenReturn(Map.of("type", "doc"));
        when(formatter.planSummary(anyString(), anyList(), anyString())).thenReturn(Map.of("type", "doc"));
        when(formatter.prLinks(any())).thenReturn(Map.of("type", "doc"));
        when(formatter.noAffinityFound(anyString())).thenReturn(Map.of("type", "doc"));

        var sage = mock(com.assurant.brain.sage.SageInquisitor.class);
        when(sage.inspect(any())).thenReturn(com.assurant.brain.sage.SageInspectionResult.empty());
        orchestrator = new JiraDrivenAutodevOrchestrator(
                jiraClient, jiraIssueMapper, formatter, runRepo, sessionRepo,
                projectRepo, affinityDetector, autodevFacade, asyncJobService, props, railChain, sage);
        org.springframework.test.util.ReflectionTestUtils.setField(orchestrator, "self", orchestrator);
    }

    @Test
    @DisplayName("START_FRESH with affinity hit + needs questions → questions comment + ANALYSIS label + PARTIAL job")
    void startFreshNeedsQuestions() {
        when(affinityDetector.detect(anyString())).thenReturn(List.of(
                new AffectedProject("ce-imei", 0.9, "matched")));
        when(runRepo.findByIssueKey("BRAIN-1")).thenReturn(Optional.empty());
        when(sessionRepo.save(any(ClarificationSession.class))).thenAnswer(inv -> {
            ClarificationSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(autodevFacade.start(anyString(), anyString(), any(), anyString())).thenReturn(
                new AutodevSessionResponse("sess-1", "req", List.of(),
                        List.of(new ClarificationQuestion("Which module?", List.of())), false));

        UUID jobId = UUID.randomUUID();
        orchestrator.beginAnalysis("BRAIN-1", jobId, true);

        verify(jiraClient).addCommentAdf(eq("bot"), eq("BRAIN-1"), any());
        ArgumentCaptor<List<String>> addCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> removeCaptor = ArgumentCaptor.forClass(List.class);
        verify(jiraClient).transitionLabels(eq("bot"), eq("BRAIN-1"),
                addCaptor.capture(), removeCaptor.capture());
        assertThat(addCaptor.getValue()).containsExactly("AI_DEV_ANALYSIS");
        assertThat(removeCaptor.getValue()).contains("AI_DEV_READY", "AI_DEV_ANALYSIS_DONE", "AI_DEV_REVIEW_READY");
        verify(asyncJobService).markPartial(eq(jobId), any());
    }

    @Test
    @DisplayName("START_FRESH confident → plan posted + ANALYSIS_DONE label + SUCCEEDED job")
    void startFreshConfident() {
        when(affinityDetector.detect(anyString())).thenReturn(List.of(
                new AffectedProject("ce-imei", 0.95, "")));
        when(runRepo.findByIssueKey("BRAIN-1")).thenReturn(Optional.empty());
        when(sessionRepo.save(any(ClarificationSession.class))).thenAnswer(inv -> {
            ClarificationSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(autodevFacade.start(anyString(), anyString(), any(), anyString()))
                .thenReturn(new AutodevSessionResponse("sess-1", "req", List.of(), List.of(), true));
        when(autodevFacade.plan(anyString())).thenReturn(new AutodevPlanResponse("sess-1", "{}"));

        UUID jobId = UUID.randomUUID();
        orchestrator.beginAnalysis("BRAIN-1", jobId, true);

        verify(autodevFacade).plan(anyString());
        verify(jiraClient).addCommentAdf(eq("bot"), eq("BRAIN-1"), any());
        ArgumentCaptor<List<String>> addCaptor = ArgumentCaptor.forClass(List.class);
        verify(jiraClient).transitionLabels(eq("bot"), eq("BRAIN-1"), addCaptor.capture(), anyList());
        assertThat(addCaptor.getValue()).containsExactly("AI_DEV_ANALYSIS_DONE");
        verify(asyncJobService).markSucceeded(eq(jobId), any());
    }

    @Test
    @DisplayName("START_FRESH with no affinity matches → no-affinity comment + ANALYSIS label + PARTIAL job")
    void startFreshNoAffinity() {
        when(affinityDetector.detect(anyString())).thenReturn(List.of());
        when(runRepo.findByIssueKey("BRAIN-1")).thenReturn(Optional.empty());
        when(sessionRepo.save(any(ClarificationSession.class))).thenAnswer(inv -> {
            ClarificationSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        UUID jobId = UUID.randomUUID();
        orchestrator.beginAnalysis("BRAIN-1", jobId, true);

        verify(formatter).noAffinityFound("AI_DEV_");
        verify(autodevFacade, never()).start(anyString(), anyString(), any(), anyString());
        verify(asyncJobService).markPartial(eq(jobId), any());
    }

    @Test
    @DisplayName("IMPLEMENT_PLAN posts PR-links comment + REVIEW_READY label + SUCCEEDED job")
    void implementPlanCreatesPrs() {
        UUID sessionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        JiraIssueRun run = new JiraIssueRun();
        run.setIssueKey("BRAIN-1");
        run.setSessionId(sessionId);
        run.setAffinityProjectIds(List.of("ce-imei"));
        run.setState(JiraRunState.AI_DEV_ANALYSIS_DONE);
        when(runRepo.findByIssueKey("BRAIN-1")).thenReturn(Optional.of(run));

        Project project = new Project();
        project.setId("ce-imei");
        project.setRepoUrl("https://github.com/x/y");
        project.setBranch("main");
        when(projectRepo.findById("ce-imei")).thenReturn(Optional.of(project));

        MultiRepoPrResult batch = new MultiRepoPrResult(batchId, BatchStatus.COMPLETED,
                1, 1, 0, 0,
                List.of(new PerRepoResult("ce-imei", "https://github.com/x/y",
                        PerRepoOutcome.SUCCESS, null, "https://github.com/x/y/pull/1",
                        1, UUID.randomUUID(), null)));
        when(autodevFacade.createPrs(anyString(), anyList(), eq("BRAIN-1"))).thenReturn(batch);

        UUID jobId = UUID.randomUUID();
        orchestrator.implementAndCreatePrs("BRAIN-1", jobId);

        verify(autodevFacade).execute(eq(sessionId.toString()), eq(List.of("ce-imei")));
        verify(autodevFacade).createPrs(eq(sessionId.toString()), anyList(), eq("BRAIN-1"));
        verify(jiraClient).addCommentAdf(eq("bot"), eq("BRAIN-1"), any());
        ArgumentCaptor<List<String>> addCaptor = ArgumentCaptor.forClass(List.class);
        verify(jiraClient).transitionLabels(eq("bot"), eq("BRAIN-1"), addCaptor.capture(), anyList());
        assertThat(addCaptor.getValue()).containsExactly("AI_DEV_REVIEW_READY");
        verify(asyncJobService).markSucceeded(eq(jobId), any());
        verify(runRepo, atLeastOnce()).save(any(JiraIssueRun.class));
    }
}
