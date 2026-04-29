package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ChunkRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.graph.CrossRepoEdgeBuilder;
import com.assurant.brain.graph.node.ClassNode;
import com.assurant.brain.graph.node.ModuleNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.retrieval.CommunitySummarizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IngestionService — CommunitySummarizer post-ingest hook")
class IngestionServiceCommunityHookTest {

    private CommunitySummarizer summarizer;
    private ProjectNodeRepository projectNodeRepository;
    private IngestionService service;

    @BeforeEach
    void setup() {
        summarizer = mock(CommunitySummarizer.class);
        projectNodeRepository = mock(ProjectNodeRepository.class);
        var props = new BrainProperties(null, null, null,
                new BrainProperties.Chunk(18000, 200),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new IngestionService(
                mock(ProjectRepository.class), mock(ChunkRepository.class),
                projectNodeRepository, mock(CrossRepoEdgeBuilder.class), new CallGraphExtractor(),
                mock(VectorStore.class), props, new com.assurant.brain.ingest.RepoKindClassifier(),
                List.of(), List.of(), new com.assurant.brain.ingest.ManifestProcessor(),
                summarizer, org.mockito.Mockito.mock(com.assurant.brain.jobs.AsyncJobService.class), org.mockito.Mockito.mock(com.assurant.brain.service.GitCloneService.class), org.mockito.Mockito.mock(com.assurant.brain.service.ProjectDetector.class));
    }

    @Test
    @DisplayName("rebuildCommunitySummariesQuietly walks ProjectNode → Module → Class FQNs and summarizes each detected community")
    void summarizesEachDetectedCommunity() {
        ProjectNode pn = projectWithClasses(List.of(
                "com.example.order.OrderService",
                "com.example.order.OrderController",
                "com.example.payment.PaymentService"));
        when(projectNodeRepository.findById("proj-1")).thenReturn(Optional.of(pn));
        var c1 = new CommunitySummarizer.Community("com.example.order", 2,
                List.of("com.example.order.OrderService", "com.example.order.OrderController"));
        var c2 = new CommunitySummarizer.Community("com.example", 1,
                List.of("com.example.order.OrderService", "com.example.order.OrderController",
                        "com.example.payment.PaymentService"));
        when(summarizer.detectCommunities(any())).thenReturn(List.of(c1, c2));

        org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "rebuildCommunitySummariesQuietly", "proj-1");

        verify(summarizer, atLeastOnce()).detectCommunities(any());
        verify(summarizer, times(2)).summarize(eq("proj-1"), any(CommunitySummarizer.Community.class));
    }

    @Test
    @DisplayName("rebuildCommunitySummariesQuietly continues after a per-community summarize failure")
    void continuesAfterPerCommunityFailure() {
        ProjectNode pn = projectWithClasses(List.of(
                "com.example.a.X", "com.example.a.Y",
                "com.example.b.W", "com.example.b.Z"));
        when(projectNodeRepository.findById("proj-1")).thenReturn(Optional.of(pn));
        var ca = new CommunitySummarizer.Community("com.example.a", 2, List.of("com.example.a.X", "com.example.a.Y"));
        var cb = new CommunitySummarizer.Community("com.example.b", 2, List.of("com.example.b.W", "com.example.b.Z"));
        when(summarizer.detectCommunities(any())).thenReturn(List.of(ca, cb));
        doThrow(new RuntimeException("LLM down"))
                .when(summarizer).summarize(eq("proj-1"), eq(ca));

        org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "rebuildCommunitySummariesQuietly", "proj-1");

        verify(summarizer).summarize(eq("proj-1"), eq(ca));
        verify(summarizer).summarize(eq("proj-1"), eq(cb));
    }

    @Test
    @DisplayName("rebuildCommunitySummariesQuietly is no-op when ProjectNode not found")
    void noOpWhenProjectMissing() {
        when(projectNodeRepository.findById("proj-1")).thenReturn(Optional.empty());

        org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "rebuildCommunitySummariesQuietly", "proj-1");

        verify(summarizer, never()).detectCommunities(any());
        verify(summarizer, never()).summarize(any(), any());
    }

    private ProjectNode projectWithClasses(List<String> classFqns) {
        ProjectNode pn = new ProjectNode();
        ModuleNode m = new ModuleNode();
        for (String fqn : classFqns) {
            ClassNode c = new ClassNode();
            c.setQualifiedName(fqn);
            m.getClasses().add(c);
        }
        pn.getModules().add(m);
        return pn;
    }
}
