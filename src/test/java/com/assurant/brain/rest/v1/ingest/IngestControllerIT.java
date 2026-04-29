package com.assurant.brain.rest.v1.ingest;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.GitCloneService;
import com.assurant.brain.service.IngestionService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("IngestController Integration Tests")
class IngestControllerIT extends BrainApplicationTests {

    @MockitoBean IngestionService ingestionService;
    @MockitoBean GitCloneService gitCloneService;
    @MockitoBean ProjectDetector projectDetector;
    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Autowired ProjectRepository projectRepository;

    @Nested
    @DisplayName("POST /api/v1/projects/ingest")
    class IngestEndpoint {

        @Test
        @DisplayName("202 Accepted — valid GitHub ingest request")
        void validRequestReturns202() throws Exception {
            Path tempDir = Files.createTempDirectory("test-clone-");
            Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");

            when(gitCloneService.clone(anyString(), anyString()))
                    .thenReturn(new GitCloneService.CloneResult(tempDir, "abc123"));
            when(projectDetector.detect(tempDir))
                    .thenReturn(new ProjectDetector.DetectedMetadata("java", "spring-boot", "gradle"));

            mvc.perform(post("/api/v1/projects/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"test","projectName":"Test","repoUrl":"https://github.com/org/repo.git"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobType").value("INGEST_PROJECT"))
                    .andExpect(jsonPath("$.jobId").exists())
                    .andExpect(jsonPath("$.streamUrl").exists())
                    .andExpect(jsonPath("$.attachedToExisting").value(false));
        }

        @Test
        @DisplayName("400 Bad Request — missing repoUrl")
        void missingRepoUrlReturns400() throws Exception {
            mvc.perform(post("/api/v1/projects/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"test","projectName":"Test"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 Bad Request — missing projectName")
        void missingProjectNameReturns400() throws Exception {
            mvc.perform(post("/api/v1/projects/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"test","repoUrl":"https://github.com/org/repo.git"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 Bad Request — blank projectId")
        void blankProjectIdReturns400() throws Exception {
            mvc.perform(post("/api/v1/projects/ingest")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"projectId":"","projectName":"Test","repoUrl":"https://github.com/org/repo.git"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/projects")
    class ListProjectsEndpoint {

        @Test
        @DisplayName("200 OK — empty list")
        void emptyList() throws Exception {
            mvc.perform(get("/api/v1/projects").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("200 OK — returns all projects")
        void returnsAll() throws Exception {
            seedProject("proj-a", "A");
            seedProject("proj-b", "B");
            mvc.perform(get("/api/v1/projects").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/projects/{projectId}")
    class GetProjectEndpoint {

        @Test
        @DisplayName("200 OK — returns project")
        void existingProject() throws Exception {
            seedProject("proj-x", "X");
            mvc.perform(get("/api/v1/projects/proj-x").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("proj-x"));
        }

        @Test
        @DisplayName("404 — non-existent project")
        void notFound() throws Exception {
            mvc.perform(get("/api/v1/projects/nope").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/projects/{projectId}/status")
    class StatusEndpoint {

        @Test
        @DisplayName("200 OK — PENDING status")
        void pendingStatus() throws Exception {
            seedProject("proj-s", "S");
            mvc.perform(get("/api/v1/projects/proj-s/status").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("404 — non-existent project")
        void notFound() throws Exception {
            mvc.perform(get("/api/v1/projects/nope/status").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
    }

    private void seedProject(String id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setLanguage("java");
        projectRepository.save(p);
    }
}
