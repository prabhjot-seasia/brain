package com.assurant.brain.rest.v1.conventions;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.IngestionService;
import com.assurant.brain.service.PlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ConventionsController Integration Tests")
class ConventionsControllerIT extends BrainApplicationTests {

    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean IngestionService ingestionService;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Test
    @DisplayName("200 OK — returns all conventions ordered by trust weight")
    void allConventionsReturns200() throws Exception {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(eq("proj-a")))
                .thenReturn(List.of(
                        buildConvention(1L, "Use constructor injection", "dependency-mgmt", "CONTRIBUTING.md", 1.5),
                        buildConvention(2L, "Log after commit", "logging", "inferred", 1.0)
                ));

        mvc.perform(get("/api/v1/projects/proj-a/conventions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].rule").value("Use constructor injection"))
                .andExpect(jsonPath("$[0].trustWeight").value(1.5))
                .andExpect(jsonPath("$[1].rule").value("Log after commit"));
    }

    @Test
    @DisplayName("200 OK — filter by category returns matching conventions only")
    void filterByCategoryReturns200() throws Exception {
        when(conventionNodeRepository.findByProjectIdAndCategory(eq("proj-a"), eq("logging")))
                .thenReturn(List.of(
                        buildConvention(1L, "Log after commit", "logging", "inferred", 1.0)
                ));

        mvc.perform(get("/api/v1/projects/proj-a/conventions")
                        .param("category", "logging")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category").value("logging"));
    }

    @Test
    @DisplayName("200 OK — empty list for project with no conventions")
    void noConventionsReturnsEmptyList() throws Exception {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(eq("proj-empty")))
                .thenReturn(Collections.emptyList());

        mvc.perform(get("/api/v1/projects/proj-empty/conventions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("200 OK — convention response contains all expected fields")
    void conventionResponseShape() throws Exception {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(eq("proj-a")))
                .thenReturn(List.of(
                        buildConvention(1L, "Use StringUtils", "string-handling", "code-style.md", 1.5)
                ));

        mvc.perform(get("/api/v1/projects/proj-a/conventions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].rule").value("Use StringUtils"))
                .andExpect(jsonPath("$[0].category").value("string-handling"))
                .andExpect(jsonPath("$[0].sourceFile").value("code-style.md"))
                .andExpect(jsonPath("$[0].trustWeight").value(1.5))
                .andExpect(jsonPath("$[0].projectId").value("proj-a"));
    }

    private ConventionNode buildConvention(Long id, String rule, String category,
                                            String sourceFile, double trustWeight) {
        ConventionNode node = new ConventionNode();
        node.setId(id);
        node.setRule(rule);
        node.setCategory(category);
        node.setSourceFile(sourceFile);
        node.setTrustWeight(trustWeight);
        node.setProjectId("proj-a");
        return node;
    }
}
