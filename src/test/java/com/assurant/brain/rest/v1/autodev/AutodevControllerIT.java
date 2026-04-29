package com.assurant.brain.rest.v1.autodev;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dto.response.AutodevExecuteResponse;
import com.assurant.brain.dto.response.AutodevPlanResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.enums.BatchStatus;
import com.assurant.brain.enums.PerRepoOutcome;
import com.assurant.brain.enums.PerRepoStage;
import com.assurant.brain.facade.autodev.AutodevFacade;
import com.assurant.brain.facade.autodev.MultiRepoPrResult;
import com.assurant.brain.facade.autodev.PerRepoResult;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AutodevController Integration Tests")
class AutodevControllerIT extends BrainApplicationTests {

    @MockitoBean AutodevFacade autodevFacade;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Test
    @DisplayName("POST /api/v1/autodev/start returns session + proposed affected projects")
    void startReturnsSession() throws Exception {
        when(autodevFacade.start(any(), any(), any(), any())).thenReturn(
                new AutodevSessionResponse("s-1", "Add retry logic",
                        List.of(Map.of("projectId", "proj-a", "confidence", 0.9, "rationale", "found")),
                        List.of(new com.assurant.brain.dto.response.ClarificationResponse.ClarificationQuestion("Sync or async?", List.of("Synchronous", "Asynchronous"))), false));

        mvc.perform(post("/api/v1/autodev/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"Add retry logic\",\"source\":\"FREE_FORM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.clarificationQuestions[0].text").value("Sync or async?"))
                .andExpect(jsonPath("$.proposedAffectedProjects[0].projectId").value("proj-a"))
                .andExpect(jsonPath("$.planReady").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/autodev/clarify forwards answers to the facade")
    void clarifyForwards() throws Exception {
        when(autodevFacade.clarify(eq("s-1"), eq("REST")))
                .thenReturn(new AutodevSessionResponse("s-1", "req",
                        List.of(), List.of(), true));

        mvc.perform(post("/api/v1/autodev/clarify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\",\"answers\":\"REST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planReady").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/autodev/plan returns the multi-repo umbrella JSON")
    void planReturnsUmbrella() throws Exception {
        when(autodevFacade.plan("s-1")).thenReturn(
                new AutodevPlanResponse("s-1", "{\"multiRepo\":true,\"projects\":[]}"));

        mvc.perform(post("/api/v1/autodev/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.multiRepoPlan").value("{\"multiRepo\":true,\"projects\":[]}"));
    }

    @Test
    @DisplayName("POST /api/v1/autodev/execute returns per-project edit summary")
    void executeReturnsSummary() throws Exception {
        when(autodevFacade.execute(eq("s-1"), any())).thenReturn(
                new AutodevExecuteResponse("s-1", List.of(
                        Map.of("projectId", "proj-a", "fileCount", 2, "nodeCount", 3, "nodeErrors", List.of()))));

        mvc.perform(post("/api/v1/autodev/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perProject[0].projectId").value("proj-a"))
                .andExpect(jsonPath("$.perProject[0].fileCount").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/autodev/create-prs returns MultiRepoPrResult with batch metadata")
    void createPrsReturnsBatch() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(autodevFacade.createPrs(eq("s-1"), anyList())).thenReturn(
                new MultiRepoPrResult(batchId, BatchStatus.PARTIAL, 2, 1, 1, 0,
                        List.of(
                                PerRepoResult.success("proj-a", "https://github.com/o/a", "https://github.com/o/a/pull/1", 1, UUID.randomUUID()),
                                PerRepoResult.failed("proj-b", "https://github.com/o/b", PerRepoStage.PR_CREATE, "github 500", UUID.randomUUID())
                        )));

        mvc.perform(post("/api/v1/autodev/create-prs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s-1\",\"repos\":[" +
                                "{\"projectId\":\"proj-a\",\"repoUrl\":\"https://github.com/o/a\",\"baseBranch\":\"main\"}," +
                                "{\"projectId\":\"proj-b\",\"repoUrl\":\"https://github.com/o/b\",\"baseBranch\":\"main\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.perRepo[0].outcome").value(PerRepoOutcome.SUCCESS.name()))
                .andExpect(jsonPath("$.perRepo[1].outcome").value(PerRepoOutcome.FAILED.name()));
    }

    @Test
    @DisplayName("GET /api/v1/autodev/batch/{id} returns 404 when batch unknown")
    void batchNotFound() throws Exception {
        mvc.perform(get("/api/v1/autodev/batch/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
