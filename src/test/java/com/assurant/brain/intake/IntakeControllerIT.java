package com.assurant.brain.intake;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.jira.JiraClient;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.GitCloneService;
import com.assurant.brain.service.IngestionService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("IntakeController Integration Tests")
class IntakeControllerIT extends BrainApplicationTests {

    @MockitoBean IngestionService ingestionService;
    @MockitoBean GitCloneService gitCloneService;
    @MockitoBean ProjectDetector projectDetector;
    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @MockitoBean JiraClient jiraClient;
    @MockitoBean ImageAnalyzerService imageAnalyzerService;

    @Test
    @DisplayName("POST /api/v1/intake/adhoc — valid text returns 200 with extracted text")
    void adhocValidText() throws Exception {
        mvc.perform(post("/api/v1/intake/adhoc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Add rate limiting to the API\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("ADHOC_TEXT"))
                .andExpect(jsonPath("$.extractedText").value("Add rate limiting to the API"))
                .andExpect(jsonPath("$.intakeId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/intake/adhoc — blank text returns 400")
    void adhocBlankText() throws Exception {
        mvc.perform(post("/api/v1/intake/adhoc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Text is required"));
    }

    @Test
    @DisplayName("POST /api/v1/intake/upload — plain text file returns extracted content")
    void uploadPlainText() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "requirements.txt", "text/plain",
                "Add authentication to the service".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/intake/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("DOCUMENT_UPLOAD"))
                .andExpect(jsonPath("$.extractedText").value("Add authentication to the service"))
                .andExpect(jsonPath("$.fileName").value("requirements.txt"));
    }

    @Test
    @DisplayName("POST /api/v1/intake/jira — missing issueKey returns 400")
    void jiraMissingKey() throws Exception {
        mvc.perform(post("/api/v1/intake/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("issueKey is required"));
    }
}
