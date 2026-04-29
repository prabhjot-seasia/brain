package com.assurant.brain.ci;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.CiRemediationAttemptRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.CiRemediationAttempt;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.enums.RemediationStatus;
import com.assurant.brain.github.GitHubClient;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CiRemediationService")
class CiRemediationServiceTest {

    private CiFailureParser ciFailureParser;
    private CodeGeneratorService codeGeneratorService;
    private GitHubClient gitHubClient;
    private PullRequestRecordRepository prRecordRepository;
    private CiRemediationAttemptRepository remediationRepository;
    private CiRemediationService service;

    @BeforeEach
    void setup() {
        ciFailureParser = mock(CiFailureParser.class);
        codeGeneratorService = mock(CodeGeneratorService.class);
        gitHubClient = mock(GitHubClient.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);
        remediationRepository = mock(CiRemediationAttemptRepository.class);

        var ci = new BrainProperties.Ci("secret", 3, 0.1, 0.1, 3.0, 15000);
        var props = new BrainProperties(null, null, null, null, null, null, null, ci, null, null, null, null, null, null, null, null, null, null, null);

        service = new CiRemediationService(ciFailureParser, codeGeneratorService, gitHubClient,
                prRecordRepository, remediationRepository, props, new ObjectMapper(),
                mock(com.assurant.brain.jobs.AsyncJobService.class));
    }

    @Test
    @DisplayName("remediate parses CI failures and pushes fixes")
    void remediateHappyPath() {
        UUID prId = UUID.randomUUID();
        PullRequestRecord pr = buildPrRecord(prId);
        when(prRecordRepository.findById(prId)).thenReturn(Optional.of(pr));
        when(remediationRepository.countByPrRecordId(prId)).thenReturn(0);
        when(remediationRepository.save(any(CiRemediationAttempt.class))).thenAnswer(inv -> {
            CiRemediationAttempt a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });

        when(gitHubClient.getWorkflowRunLogs("owner", "repo", 100L)).thenReturn("test failed logs");
        when(ciFailureParser.parse(anyString())).thenReturn(
                new CiFailureParser.FailureAnalysis(List.of("TestFoo failed"), "1 failure", "TEST_FAILURE"));
        when(codeGeneratorService.fixCode(anyMap(), anyList(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "fixed"));

        CiRemediationAttempt result = service.remediate(prId, 100L);

        assertThat(result.getStatus()).isEqualTo(RemediationStatus.PUSHED);
        assertThat(result.getAttemptNumber()).isEqualTo(1);
        verify(gitHubClient).createOrUpdateFile(eq("owner"), eq("repo"), eq("brain/codegen-1"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("remediate returns EXHAUSTED when max attempts reached")
    void remediateExhausted() {
        UUID prId = UUID.randomUUID();
        PullRequestRecord pr = buildPrRecord(prId);
        when(prRecordRepository.findById(prId)).thenReturn(Optional.of(pr));
        when(remediationRepository.countByPrRecordId(prId)).thenReturn(3);
        when(remediationRepository.save(any(CiRemediationAttempt.class))).thenAnswer(inv -> {
            CiRemediationAttempt a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });

        CiRemediationAttempt result = service.remediate(prId, 200L);

        assertThat(result.getStatus()).isEqualTo(RemediationStatus.EXHAUSTED);
        verifyNoInteractions(ciFailureParser);
    }

    @Test
    @DisplayName("remediate returns RESOLVED when no failures found")
    void remediateNoFailures() {
        UUID prId = UUID.randomUUID();
        PullRequestRecord pr = buildPrRecord(prId);
        when(prRecordRepository.findById(prId)).thenReturn(Optional.of(pr));
        when(remediationRepository.countByPrRecordId(prId)).thenReturn(0);
        when(remediationRepository.save(any(CiRemediationAttempt.class))).thenAnswer(inv -> {
            CiRemediationAttempt a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });

        when(gitHubClient.getWorkflowRunLogs("owner", "repo", 100L)).thenReturn("all passed");
        when(ciFailureParser.parse(anyString())).thenReturn(
                new CiFailureParser.FailureAnalysis(List.of(), "clean", "MIXED"));

        CiRemediationAttempt result = service.remediate(prId, 100L);
        assertThat(result.getStatus()).isEqualTo(RemediationStatus.RESOLVED);
    }

    @Test
    @DisplayName("remediate throws when PR record not found")
    void remediateNotFound() {
        UUID prId = UUID.randomUUID();
        when(prRecordRepository.findById(prId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remediate(prId, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PullRequestRecord buildPrRecord(UUID id) {
        PullRequestRecord pr = new PullRequestRecord();
        pr.setId(id);
        pr.setRepoUrl("https://github.com/owner/repo");
        pr.setBaseBranch("main");
        pr.setBranchName("brain/codegen-1");
        pr.setPrNumber(42);
        pr.setStatus(PrStatus.CREATED);
        pr.setGeneratedFiles(Map.of("src/Foo.java", "original code"));
        return pr;
    }
}
