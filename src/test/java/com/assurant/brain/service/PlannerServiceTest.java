package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.assurant.brain.enums.RailType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlannerService")
class PlannerServiceTest {

    private ChatModel chatModel;
    private VectorStore vectorStore;
    private ConventionNodeRepository conventionNodeRepository;
    private ProjectNodeRepository projectNodeRepository;
    private com.assurant.brain.cache.SemanticCacheService semanticCacheService;
    private com.assurant.brain.monitor.TokenUsageTracker tokenUsageTracker;
    private RailChain railChain;
    private PlannerService service;

    @BeforeEach
    void setup() {
        chatModel = mock(ChatModel.class);
        vectorStore = mock(VectorStore.class);
        conventionNodeRepository = mock(ConventionNodeRepository.class);
        projectNodeRepository = mock(ProjectNodeRepository.class);
        semanticCacheService = mock(com.assurant.brain.cache.SemanticCacheService.class);
        tokenUsageTracker = mock(com.assurant.brain.monitor.TokenUsageTracker.class);
        railChain = mock(RailChain.class);
        when(semanticCacheService.get(anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        when(railChain.applyPreLlm(any(RailContext.class))).thenAnswer(inv -> {
            RailContext ctx = inv.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of(RailResult.pass(RailType.LENGTH)));
        });
        when(railChain.applyPostLlm(any(RailContext.class))).thenAnswer(inv -> {
            RailContext ctx = inv.getArgument(0);
            return new RailChain.ChainResult(ctx, List.of(RailResult.pass(RailType.SCHEMA)));
        });

        var rag = new BrainProperties.Rag(5, 3, 4000, 1.5, 1.0, 10, 5);
        var props = new BrainProperties(null, null, rag, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        var adaptivePromptBuilder = mock(com.assurant.brain.codegen.AdaptivePromptBuilder.class);
        when(adaptivePromptBuilder.buildAdaptiveSection(anyString())).thenReturn("");
        var solutionPatternService = mock(com.assurant.brain.learning.SolutionPatternService.class);
        when(solutionPatternService.findFewShotExamples(anyString(), anyString())).thenReturn(java.util.Optional.empty());

        Executor directExecutor = Runnable::run;
        var incidentRepo = mock(com.assurant.brain.graph.repository.IncidentNodeRepository.class);
        var reviewPatternRepo = mock(com.assurant.brain.graph.repository.ReviewPatternNodeRepository.class);
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString())).thenReturn(java.util.List.of());
        when(reviewPatternRepo.findByProjectIdAndStatusOrderByOccurrences(anyString(), any(), anyInt()))
                .thenReturn(java.util.List.of());
        var hybridRetriever = mock(com.assurant.brain.retrieval.HybridRetrieverService.class);
        when(hybridRetriever.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of());
        var crossEncoder = mock(com.assurant.brain.retrieval.CrossEncoderReRanker.class);
        when(crossEncoder.rerank(anyString(), anyString(), any(), anyInt())).thenReturn(java.util.List.of());
        var iterative = mock(com.assurant.brain.retrieval.IterativeContextEnricher.class);
        when(iterative.enrich(anyString(), anyString(), anyString())).thenReturn(java.util.List.of());
        var oracle = mock(com.assurant.brain.avenger.oracle.OraclePromptOptimizer.class);
        when(oracle.computeBudget(anyString())).thenReturn(
                new com.assurant.brain.avenger.oracle.OraclePromptOptimizer.RetrievalBudget(50, 5, "DEFAULT", 12500, 1250, 100));
        var communitySummaryRepo = mock(com.assurant.brain.graph.repository.CommunitySummaryNodeRepository.class);
        when(communitySummaryRepo.findByProjectId(anyString())).thenReturn(java.util.List.of());

        service = new PlannerService(chatModel, vectorStore, conventionNodeRepository,
                projectNodeRepository, incidentRepo, reviewPatternRepo,
                props, semanticCacheService, tokenUsageTracker,
                adaptivePromptBuilder, solutionPatternService,
                railChain, new ObjectMapper(),
                hybridRetriever, crossEncoder, iterative, oracle, communitySummaryRepo,
                directExecutor);
    }

    @Test
    @DisplayName("generatePlan retrieves RAG context, conventions, graph context, and calls ChatModel")
    void generatePlanHappyPath() {
        Document codeDoc = new Document("class Foo {}", Map.of("chunkName", "com.example.Foo", "sourceType", "CODE"));
        Document docDoc = new Document("# README", Map.of("chunkName", "README.md", "sourceType", "DOC"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(codeDoc))
                .thenReturn(List.of(docDoc));

        ConventionNode convention = new ConventionNode();
        convention.setRule("Use Lombok @RequiredArgsConstructor");
        convention.setSourceFile("CONTRIBUTING.md");
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1"))
                .thenReturn(List.of(convention));

        when(projectNodeRepository.findAffectedClasses("proj-1", "Add rate limiting"))
                .thenReturn(List.of("ApiController", "RateLimiter"));

        mockChatResponse("{\"requirement\":\"Add rate limiting\",\"steps\":[]}");

        String plan = service.generatePlan("proj-1", "Add rate limiting", List.of());

        assertThat(plan).contains("Add rate limiting");
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
        verify(conventionNodeRepository).findByProjectIdOrderByTrustWeightDesc("proj-1");
        verify(projectNodeRepository).findAffectedClasses(eq("proj-1"), anyString());
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generatePlan includes clarification rounds in the prompt")
    void generatePlanWithClarificationRounds() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        mockChatResponse("{\"requirement\":\"test\"}");

        List<Map<String, Object>> rounds = List.of(
                Map.of("questions", "Which module?", "answers", "The auth module"),
                Map.of("questions", "REST or gRPC?", "answers", "REST")
        );

        String plan = service.generatePlan("proj-1", "Add auth", rounds);
        assertThat(plan).isNotBlank();
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generatePlan handles empty graph context")
    void generatePlanEmptyGraphContext() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        mockChatResponse("{\"steps\":[]}");

        String plan = service.generatePlan("proj-1", "Add something", List.of());
        assertThat(plan).isNotBlank();
    }

    @Test
    @DisplayName("explainProject retrieves context and uses EXPLAIN_SYSTEM_PROMPT")
    void explainProjectHappyPath() {
        Document doc = new Document("# Architecture\nSpring Boot app", Map.of("chunkName", "README.md", "sourceType", "DOC"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc))
                .thenReturn(List.of());

        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1")).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(eq("proj-1"), anyString())).thenReturn(List.of());

        mockChatResponse("## Overview\nThis is a Spring Boot application...");

        String explanation = service.explainProject("proj-1", "Explain this project");

        assertThat(explanation).contains("Spring Boot");
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("explainProject handles long requirement for graph keyword extraction")
    void explainProjectLongRequirement() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        mockChatResponse("## Summary\nNot much context available.");

        String longReq = "Explain this project in great detail including all services, " +
                "controllers, repositories, and configuration classes that are present in the codebase";
        String explanation = service.explainProject("proj-1", longReq);

        assertThat(explanation).isNotBlank();
        verify(projectNodeRepository).findAffectedClasses(eq("proj-1"),
                argThat(s -> s.length() == 50));
    }

    @Test
    @DisplayName("generatePlan with empty clarification rounds formats as 'No clarification needed'")
    void emptyRoundsFormatting() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        mockChatResponse("{\"plan\":\"ok\"}");

        service.generatePlan("proj-1", "Test", List.of());

        verify(chatModel).call(argThat((Prompt p) -> {
            String userMsg = p.getInstructions().stream()
                    .filter(m -> m.getMessageType().name().equals("USER"))
                    .map(Message::getText)
                    .findFirst().orElse("");
            return userMsg.contains("No clarification needed");
        }));
    }

    @Test
    @DisplayName("retrieveConventions formats conventions with source annotation")
    void conventionsFormatting() {
        ConventionNode c1 = new ConventionNode();
        c1.setRule("Use constructor injection");
        c1.setSourceFile("CONTRIBUTING.md");
        ConventionNode c2 = new ConventionNode();
        c2.setRule("Log4j2 not Logback");
        c2.setSourceFile("inferred from 12 occurrences");

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc("proj-1"))
                .thenReturn(List.of(c1, c2));
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        mockChatResponse("{\"ok\":true}");

        service.generatePlan("proj-1", "Test", List.of());

        verify(chatModel).call(argThat((Prompt p) -> {
            String userMsg = p.getInstructions().stream()
                    .filter(m -> m.getMessageType().name().equals("USER"))
                    .map(Message::getText)
                    .findFirst().orElse("");
            return userMsg.contains("Use constructor injection [source: CONTRIBUTING.md]")
                    && userMsg.contains("Log4j2 not Logback [source: inferred from 12 occurrences]");
        }));
    }

    @Test
    @DisplayName("generatePlan invokes RailChain pre-LLM and post-LLM")
    void generatePlanAppliesRailChain() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        mockChatResponse("{\"requirement\":\"x\"}");

        service.generatePlan("proj-1", "Add feature", List.of());

        verify(railChain).applyPreLlm(argThat(ctx -> "PlannerService".equals(ctx.sourceService())));
        verify(railChain).applyPostLlm(argThat(ctx ->
                "PlannerService".equals(ctx.sourceService())
                        && "schemas/plan.json".equals(ctx.metadata().get("outputSchemaResource"))));
    }

    @Test
    @DisplayName("explainProject invokes RailChain pre-LLM and post-LLM")
    void explainProjectAppliesRailChain() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        mockChatResponse("## Summary\n...");

        service.explainProject("proj-1", "Explain this");

        verify(railChain).applyPreLlm(any(RailContext.class));
        verify(railChain).applyPostLlm(any(RailContext.class));
    }

    @Test
    @DisplayName("generateMultiRepoPlan fans out over projectIds and aggregates into umbrella JSON")
    void multiRepoPlanAggregates() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        mockChatResponse("{\"requirement\":\"shared\",\"steps\":[]}");

        String umbrella = service.generateMultiRepoPlan(
                List.of("backend", "frontend"), "shared", List.of());

        ObjectMapper om = new ObjectMapper();
        var root = om.readTree(umbrella);
        assertThat(root.get("multiRepo").asBoolean()).isTrue();
        assertThat(root.get("requirement").asText()).isEqualTo("shared");
        assertThat(root.get("projects").size()).isEqualTo(2);
        assertThat(root.get("projects").get(0).get("status").asText()).isEqualTo("OK");
        assertThat(root.get("projects").get(1).get("status").asText()).isEqualTo("OK");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generateMultiRepoPlan tolerates a per-project failure and marks it FAILED in umbrella")
    void multiRepoPlanTolerPerProjectFailure() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        Generation g = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(msg.getText()).thenReturn("{\"requirement\":\"ok\"}");
        when(g.getOutput()).thenReturn(msg);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(g);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(resp)
                .thenThrow(new RuntimeException("LLM down"));

        String umbrella = service.generateMultiRepoPlan(
                List.of("backend", "frontend"), "shared", List.of());

        ObjectMapper om = new ObjectMapper();
        var projects = om.readTree(umbrella).get("projects");
        assertThat(projects.get(0).get("status").asText()).isEqualTo("OK");
        assertThat(projects.get(1).get("status").asText()).isEqualTo("FAILED");
        assertThat(projects.get(1).get("error").asText()).contains("LLM down");
    }

    @Test
    @DisplayName("generateMultiRepoPlan schema-validates the umbrella via RailChain")
    void multiRepoPlanAppliesSchemaRail() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        mockChatResponse("{\"requirement\":\"x\",\"steps\":[]}");

        service.generateMultiRepoPlan(List.of("backend"), "shared", List.of());

        verify(railChain).applyPostLlm(argThat(ctx ->
                "PlannerService".equals(ctx.sourceService())
                        && "schemas/multi-repo-plan.json".equals(ctx.metadata().get("outputSchemaResource"))));
    }

    @Test
    @DisplayName("generatePlan injects matching IncidentNodes as PAST INCIDENTS in the prompt")
    void generatePlanInjectsNegativeKnowledge() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        com.assurant.brain.graph.node.IncidentNode incident = new com.assurant.brain.graph.node.IncidentNode();
        incident.setId("inc-42");
        incident.setProjectId("proj-1");
        incident.setTitle("OrderService NPE on retry");
        incident.setSeverity("SEV2");
        incident.setOccurredAt("2026-03-01");
        incident.setRootCauseSummary("Null map default in retry path");
        incident.setAffectedClassHints(java.util.List.of("OrderService"));

        var incidentRepo = (com.assurant.brain.graph.repository.IncidentNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "incidentNodeRepository");
        when(incidentRepo.findByProjectIdAndClassHintMatch("proj-1", "OrderService"))
                .thenReturn(java.util.List.of(incident));

        mockChatResponse("{\"steps\":[]}");

        service.generatePlan("proj-1", "Fix the OrderService retry bug", List.of());

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("PAST INCIDENTS")
                .contains("OrderService NPE on retry")
                .contains("Null map default in retry path")
                .contains("SEV2");
    }

    @Test
    @DisplayName("generatePlan injects ReviewPatternNodes as REVIEW-DERIVED CONVENTIONS in the prompt")
    void generatePlanInjectsReviewPatterns() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        com.assurant.brain.graph.node.ReviewPatternNode pattern = new com.assurant.brain.graph.node.ReviewPatternNode();
        pattern.setId("rp-7");
        pattern.setProjectId("proj-1");
        pattern.setPhrase("Use constructor injection, not @Autowired fields");
        pattern.setOccurrences(12);
        pattern.setStatus("APPROVED");

        var reviewRepo = (com.assurant.brain.graph.repository.ReviewPatternNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "reviewPatternNodeRepository");
        when(reviewRepo.findByProjectIdAndStatusOrderByOccurrences(eq("proj-1"), any(), anyInt()))
                .thenReturn(java.util.List.of(pattern));

        mockChatResponse("{\"steps\":[]}");

        service.generatePlan("proj-1", "Add a new endpoint", List.of());

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("REVIEW-DERIVED CONVENTIONS")
                .contains("Use constructor injection, not @Autowired fields")
                .contains("12");
    }

    @Test
    @DisplayName("retrieveRagContext routes through Hybrid + CrossEncoder when Hybrid returns hits")
    void retrieveRagContextUsesRerankedHits() {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        var hybridRetriever = (com.assurant.brain.retrieval.HybridRetrieverService)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "hybridRetrieverService");
        var crossEncoder = (com.assurant.brain.retrieval.CrossEncoderReRanker)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "crossEncoderReRanker");

        Document hit = new Document("class OrderService { void place() {} }",
                Map.of("chunkName", "OrderService", "sourceType", "CODE", "filePath", "OrderService.java"));
        var scored = new com.assurant.brain.retrieval.HybridRetrieverService.ScoredDocument(hit, 0.9);
        when(hybridRetriever.hybridSearch(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of(scored));
        when(crossEncoder.rerank(anyString(), anyString(), any(), anyInt()))
                .thenReturn(java.util.List.of(
                        new com.assurant.brain.retrieval.CrossEncoderReRanker.RerankedDocument(hit, 0.95)));

        mockChatResponse("{\"steps\":[]}");

        service.generatePlan("proj-1", "Touch OrderService.place", List.of());

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("OrderService").contains("place");
        verify(crossEncoder).rerank(anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("generatePlan invokes IterativeContextEnricher with the draft plan")
    void iterativeEnricherInvokedWithDraftPlan() {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var iterative = (com.assurant.brain.retrieval.IterativeContextEnricher)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "iterativeContextEnricher");

        mockChatResponse("{\"requirement\":\"X\",\"steps\":[]}");

        service.generatePlan("proj-1", "Add X", List.of());

        verify(iterative).enrich(eq("proj-1"), eq("Add X"), argThat(plan -> plan.contains("requirement")));
    }

    @Test
    @DisplayName("explainProject injects CommunitySummaryNode rollups into the prompt")
    void explainProjectInjectsCommunitySummaries() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        var communityRepo = (com.assurant.brain.graph.repository.CommunitySummaryNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "communitySummaryNodeRepository");
        var s1 = new com.assurant.brain.graph.node.CommunitySummaryNode();
        s1.setLevel(2); s1.setCommunityKey("com.example.order"); s1.setSummary("Order package handles checkout.");
        var s2 = new com.assurant.brain.graph.node.CommunitySummaryNode();
        s2.setLevel(1); s2.setCommunityKey("com.example"); s2.setSummary("Top-level package; aggregates services.");
        when(communityRepo.findByProjectId("proj-1")).thenReturn(java.util.List.of(s1, s2));

        mockChatResponse("## Summary\nok");

        service.explainProject("proj-1", "Explain this");

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("COMMUNITY SUMMARIES")
                .contains("com.example.order")
                .contains("Order package handles checkout")
                .contains("Top-level package");
    }

    @Test
    @DisplayName("explainProject surfaces fallback message when CommunitySummary repo errors")
    void explainProjectFallbackOnCommunityRepoError() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(anyString())).thenReturn(List.of());
        when(projectNodeRepository.findAffectedClasses(anyString(), anyString())).thenReturn(List.of());

        var communityRepo = (com.assurant.brain.graph.repository.CommunitySummaryNodeRepository)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "communitySummaryNodeRepository");
        when(communityRepo.findByProjectId("proj-1")).thenThrow(new RuntimeException("neo4j down"));

        mockChatResponse("ok");

        service.explainProject("proj-1", "Explain");

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String text = captor.getValue().getInstructions().stream()
                .map(Message::getText).reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("community summaries unavailable");
    }

    @Test
    @DisplayName("generateMultiRepoPlan rejects an empty project list")
    void multiRepoPlanRejectsEmpty() {
        assertThatThrownByPrivate(() -> service.generateMultiRepoPlan(List.of(), "req", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownByPrivate(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(callable);
    }

    private void mockChatResponse(String text) {
        Generation generation = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage output =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }
}
