package com.assurant.brain.facade;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.codegen.SelfReviewLoop;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.exceptions.SessionNotFoundException;
import com.assurant.brain.github.PrCreationService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PrCreationFacade")
class PrCreationFacadeTest {

    private CodeGeneratorService codeGeneratorService;
    private SelfReviewLoop selfReviewLoop;
    private PrCreationService prCreationService;
    private ClarificationSessionRepository sessionRepository;
    private PullRequestRecordRepository prRecordRepository;
    private PrCreationFacade facade;

    @BeforeEach
    void setup() {
        codeGeneratorService = mock(CodeGeneratorService.class);
        selfReviewLoop = mock(SelfReviewLoop.class);
        prCreationService = mock(PrCreationService.class);
        sessionRepository = mock(ClarificationSessionRepository.class);
        prRecordRepository = mock(PullRequestRecordRepository.class);

        var github = new BrainProperties.GitHub("token", "https://api.github.com", 3);
        var props = new BrainProperties(null, null, null, null, github, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        facade = new PrCreationFacade(
                codeGeneratorService, selfReviewLoop, prCreationService,
                sessionRepository, prRecordRepository,
                props, new ObjectMapper(),
                new com.assurant.brain.codegen.PrDescriptionRenderer());
    }

    @Test
    @DisplayName("createPr generates code, delegates self-review, and creates PR")
    void createPrHappyPath() {
        UUID sessionId = UUID.randomUUID();
        ClarificationSession session = buildSession(sessionId, SessionStatus.COMPLETE);

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(prRecordRepository.save(any(PullRequestRecord.class))).thenAnswer(inv -> {
            PullRequestRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        Map<String, String> files = Map.of("src/Foo.java", "code");
        when(codeGeneratorService.generateCode(anyString(), anyString(), anyString())).thenReturn(files);
        when(selfReviewLoop.run(any(PullRequestRecord.class), anyMap(), anyString(), anyInt()))
                .thenReturn(files);
        when(prCreationService.createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(new PrCreationService.PrResult("brain/codegen-1", 42, "https://github.com/o/r/pull/42"));

        PullRequestRecord result = facade.createPr(sessionId, "https://github.com/o/r", "main");

        assertThat(result.getStatus()).isEqualTo(PrStatus.CREATED);
        assertThat(result.getPrNumber()).isEqualTo(42);
        assertThat(result.getPrUrl()).isEqualTo("https://github.com/o/r/pull/42");
        verify(codeGeneratorService).generateCode(anyString(), anyString(), anyString());
        verify(selfReviewLoop).run(any(PullRequestRecord.class), anyMap(), anyString(), eq(3));
        verify(prCreationService).createPullRequest(anyString(), anyString(), anyMap(), anyString(), anyString());
    }

    @Test
    @DisplayName("createPr throws when session not found")
    void createPrSessionNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.createPr(sessionId, "url", "main"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("createPr throws when session not COMPLETE")
    void createPrSessionNotComplete() {
        UUID sessionId = UUID.randomUUID();
        ClarificationSession session = buildSession(sessionId, SessionStatus.PENDING);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.createPr(sessionId, "url", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETE");
    }

    @Test
    @DisplayName("createPr throws when session has no final plan")
    void createPrNoPlan() {
        UUID sessionId = UUID.randomUUID();
        ClarificationSession session = buildSession(sessionId, SessionStatus.COMPLETE);
        session.setFinalPlan(null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> facade.createPr(sessionId, "url", "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no final plan");
    }

    @Test
    @DisplayName("createPr marks FAILED when code generation throws")
    void createPrFailure() {
        UUID sessionId = UUID.randomUUID();
        ClarificationSession session = buildSession(sessionId, SessionStatus.COMPLETE);

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(prRecordRepository.save(any(PullRequestRecord.class))).thenAnswer(inv -> {
            PullRequestRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        when(codeGeneratorService.generateCode(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("LLM failed"));

        assertThatThrownBy(() -> facade.createPr(sessionId, "https://github.com/o/r", "main"))
                .isInstanceOf(IllegalStateException.class);

        verify(prRecordRepository, atLeast(2)).save(argThat(r -> r.getStatus() == PrStatus.FAILED));
    }

    private ClarificationSession buildSession(UUID id, SessionStatus status) {
        ClarificationSession session = new ClarificationSession();
        session.setId(id);
        session.setProjectId("proj-1");
        session.setRequirement("Add retry logic");
        session.setStatus(status);
        session.setFinalPlan(Map.of("requirement", "Add retry logic", "steps", List.of()));
        return session;
    }
}
