package com.assurant.brain.rest.v1.analyze;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("POST /api/v1/analyze — Error Handling")
class AnalyzeErrorHandlingIT extends BrainApplicationTests {

    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Test
    @DisplayName("400 — missing projectId field")
    void missingProjectIdReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "requirement": "Add logging" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400 — missing requirement field")
    void missingRequirementReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": "proj-x" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400 — blank projectId field")
    void blankProjectIdReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": "  ", "requirement": "Add logging" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400 — blank requirement field")
    void blankRequirementReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": "proj-x", "requirement": "  " }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("404 — invalid session ID not found")
    void invalidSessionIdReturns404() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": "proj-x",
                                  "requirement": "Test",
                                  "sessionId": "00000000-0000-0000-0000-000000000099"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Session not found: 00000000-0000-0000-0000-000000000099"));
    }

    @Test
    @DisplayName("400 — malformed JSON body")
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("415 — wrong content type")
    void wrongContentTypeReturns415() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("400 — empty body")
    void emptyBodyReturns400() throws Exception {
        mvc.perform(post("/api/v1/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }
}
