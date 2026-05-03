package com.assurant.brain.avenger;

import com.assurant.brain.avenger.dto.AvengerRequest;
import com.assurant.brain.avenger.dto.AvengerResponse;
import com.assurant.brain.codegen.AstValidator;
import com.assurant.brain.codegen.ConventionChecker;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.AvengerReviewRepository;
import com.assurant.brain.domain.AvengerReview;
import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AvengerReviewer")
class AvengerReviewerTest {

    private ChatModel chatModel;
    private AvengerPersonaLoader personaLoader;
    private AvengerReviewRepository repository;
    private AvengerReviewer reviewer;
    private com.assurant.brain.learning.AvengerMemory memory;
    private com.assurant.brain.dao.LearningEventRepository learningRepo;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        personaLoader = mock(AvengerPersonaLoader.class);
        repository = mock(AvengerReviewRepository.class);
        TokenUsageTracker tracker = mock(TokenUsageTracker.class);
        BrainProperties props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);        RailChain railChain = mock(RailChain.class);
        when(railChain.applyPreLlm(any())).thenAnswer(i -> {
            RailContext ctx = i.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        when(railChain.applyPostLlm(any())).thenAnswer(i -> {
            RailContext ctx = i.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of());
        });
        AstValidator ast = new AstValidator();
        ConventionChecker conv = new ConventionChecker();

        when(personaLoader.load(any())).thenReturn("You are a test persona.");
        when(repository.save(any(AvengerReview.class))).thenAnswer(i -> {
            AvengerReview r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        learningRepo = mock(com.assurant.brain.dao.LearningEventRepository.class);
        memory = mock(com.assurant.brain.learning.AvengerMemory.class);
        when(memory.getMemory(any(), any())).thenAnswer(i ->
                com.assurant.brain.learning.AvengerMemorySnapshot.empty(i.getArgument(0), i.getArgument(1)));
        com.assurant.brain.codegen.AdaptivePromptBuilder adaptive =
                mock(com.assurant.brain.codegen.AdaptivePromptBuilder.class);
        when(adaptive.buildAdaptiveSection(any(), any(com.assurant.brain.enums.AvengerType.class))).thenReturn("");
        var testRunRepo = mock(com.assurant.brain.graph.repository.TestRunNodeRepository.class);
        when(testRunRepo.findFlakyTests(any(), anyDouble(), anyInt())).thenReturn(java.util.List.of());
        var sage = mock(com.assurant.brain.sage.SageInquisitor.class);
        when(sage.readinessFor(any())).thenReturn(new com.assurant.brain.sage.SageInquisitor.ContextReadinessReport(
                0, 0, 0, 0, java.util.List.of(), "no project knowledge gaps"));
        reviewer = new AvengerReviewer(chatModel, new ObjectMapper(), personaLoader, repository, tracker,
                props, railChain, ast, conv, learningRepo, memory, adaptive, testRunRepo,
                mock(com.assurant.brain.jobs.AsyncJobService.class),
                new MirageReviewer(new com.assurant.brain.codegen.StyleFingerprintBuilder()),
                mock(org.springframework.ai.vectorstore.VectorStore.class),
                sage,
                new com.assurant.brain.avenger.checks.ItControllerEntryCheck());
    }

    @Test
    @DisplayName("STARK uses AST + convention delegates, not LLM")
    void starkUsesDelegates() {
        AvengerResponse response = reviewer.review(new AvengerRequest(
                AvengerType.STARK, "proj-1",
                "package com.example;\n\npublic class Foo {}", null));

        assertThat(response.avenger()).isEqualTo(AvengerType.STARK);
        assertThat(response.verdict()).isEqualTo(AvengerVerdict.APPROVED);
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("STARK flags @Autowired field injection as CHANGES_REQUESTED")
    void starkFlagsFieldInjection() {
        String code = """
                package com.example;
                import org.springframework.beans.factory.annotation.Autowired;
                public class BadService {
                    @Autowired
                    private String dependency;
                }
                """;
        AvengerResponse response = reviewer.review(new AvengerRequest(
                AvengerType.STARK, "proj-1", code, null));

        assertThat(response.verdict()).isEqualTo(AvengerVerdict.CHANGES_REQUESTED);
        assertThat(response.issues()).isNotEmpty();
    }

    @Test
    @DisplayName("HAWKEYE uses LLM with parsed JSON verdict")
    void hawkeyeUsesLlm() {
        mockChatResponse("""
                {"verdict":"APPROVED","issues":[],"summary":"No security issues found"}
                """);

        AvengerResponse response = reviewer.review(new AvengerRequest(
                AvengerType.HAWKEYE, "proj-1", "public class Foo {}", null));

        assertThat(response.verdict()).isEqualTo(AvengerVerdict.APPROVED);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("HAWKEYE with malformed LLM response returns CHANGES_REQUESTED")
    void hawkeyeMalformedResponse() {
        mockChatResponse("not valid json at all");

        AvengerResponse response = reviewer.review(new AvengerRequest(
                AvengerType.HAWKEYE, "proj-1", "code", null));

        assertThat(response.verdict()).isEqualTo(AvengerVerdict.CHANGES_REQUESTED);
        assertThat(response.issues()).isNotEmpty();
    }

    @Test
    @DisplayName("persists every review with verdict and issues")
    void persistsReview() {
        mockChatResponse("""
                {"verdict":"BLOCKED","issues":["critical"],"summary":"blocked"}
                """);

        reviewer.review(new AvengerRequest(AvengerType.FURY, "proj-1", "doc content", null));

        verify(repository).save(any(AvengerReview.class));
    }

    @Test
    @DisplayName("emits LearningEvent and invalidates memory on non-APPROVED verdict")
    void emitsLearningEventAndInvalidates() {
        mockChatResponse("""
                {"verdict":"CHANGES_REQUESTED","issues":["field injection detected","log4j2 missing"],"summary":"2 issues"}
                """);

        reviewer.review(new AvengerRequest(AvengerType.HAWKEYE, "proj-1", "some code", null));

        verify(learningRepo, times(2)).save(any(com.assurant.brain.domain.LearningEvent.class));
        verify(memory).invalidate(AvengerType.HAWKEYE, "proj-1");
    }

    @Test
    @DisplayName("does NOT emit LearningEvent or invalidate memory on APPROVED verdict")
    void approvedVerdictSkipsLearning() {
        mockChatResponse("""
                {"verdict":"APPROVED","issues":[],"summary":"clean"}
                """);

        reviewer.review(new AvengerRequest(AvengerType.HAWKEYE, "proj-1", "clean code", null));

        verify(learningRepo, never()).save(any(com.assurant.brain.domain.LearningEvent.class));
        verify(memory, never()).invalidate(any(), any());
    }

    @Test
    @DisplayName("WIDOW prompt includes flaky-tests preamble when project has flaky tests")
    void widowPromptIncludesFlakyTests() {
        com.assurant.brain.graph.node.TestRunNode flaky = new com.assurant.brain.graph.node.TestRunNode();
        flaky.setId("tr-1");
        flaky.setProjectId("proj-1");
        flaky.setTestFqn("com.example.OrderServiceTest.flakyEdge");
        flaky.setTotalRuns(10);
        flaky.setFailCount(3);
        flaky.setFlakinessScore(0.3);

        var testRunRepo = (com.assurant.brain.graph.repository.TestRunNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(reviewer, "testRunNodeRepository");
        when(testRunRepo.findFlakyTests(eq("proj-1"), anyDouble(), anyInt()))
                .thenReturn(java.util.List.of(flaky));

        mockChatResponse("{\"verdict\":\"APPROVED\",\"issues\":[],\"summary\":\"ok\"}");

        reviewer.review(new AvengerRequest(AvengerType.WIDOW, "proj-1", "code", null));

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String systemText = captor.getValue().getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(systemText).contains("KNOWN FLAKY TESTS")
                .contains("com.example.OrderServiceTest.flakyEdge")
                .contains("0.30");
    }

    @Test
    @DisplayName("non-WIDOW reviews do NOT include flaky-tests preamble")
    void nonWidowOmitsFlakyPreamble() {
        var testRunRepo = (com.assurant.brain.graph.repository.TestRunNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(reviewer, "testRunNodeRepository");
        com.assurant.brain.graph.node.TestRunNode flaky = new com.assurant.brain.graph.node.TestRunNode();
        flaky.setTestFqn("com.example.OrderServiceTest.flakyEdge");
        flaky.setFlakinessScore(0.3);
        when(testRunRepo.findFlakyTests(any(), anyDouble(), anyInt()))
                .thenReturn(java.util.List.of(flaky));

        mockChatResponse("{\"verdict\":\"APPROVED\",\"issues\":[],\"summary\":\"ok\"}");

        reviewer.review(new AvengerRequest(AvengerType.HAWKEYE, "proj-1", "code", null));

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String systemText = captor.getValue().getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(systemText).doesNotContain("KNOWN FLAKY TESTS");
        verify(testRunRepo, never()).findFlakyTests(any(), anyDouble(), anyInt());
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
