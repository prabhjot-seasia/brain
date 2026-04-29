package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.Project;
import com.assurant.brain.service.GitCloneService;
import com.assurant.brain.service.ProjectDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class IngestSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired private SharedTestState state;
    @Autowired private GitCloneService gitCloneService;
    @Autowired private ProjectDetector projectDetector;

    @Given("a project {string} with name {string} exists in the database")
    public void projectExistsInDb(String projectId, String projectName) {
        Project project = new Project();
        project.setId(projectId);
        project.setName(projectName);
        project.setLanguage("java");
        project.setFramework("spring-boot");
        project.setBuildTool("gradle");
        projectRepository.save(project);
    }

    @When("I submit an ingest request for project {string} with name {string} and repo {string}")
    public void submitIngestRequest(String projectId, String projectName, String repoUrl) throws Exception {
        Path tempDir = Files.createTempDirectory("bdd-clone-");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'org.springframework.boot' }");

        when(gitCloneService.clone(anyString(), anyString()))
                .thenReturn(new GitCloneService.CloneResult(tempDir, "abc123def456"));
        when(projectDetector.detect(tempDir))
                .thenReturn(new ProjectDetector.DetectedMetadata("java", "spring-boot", "gradle"));

        String body = """
                {"projectId":"%s","projectName":"%s","repoUrl":"%s"}
                """.formatted(projectId, projectName, repoUrl);

        captureResponse(http.postJson("/api/v1/projects/ingest", body));
    }

    @When("I submit an ingest request without a repo URL")
    public void submitIngestWithoutRepoUrl() {
        String body = """
                {"projectId":"test","projectName":"Test"}
                """;
        captureResponse(http.postJson("/api/v1/projects/ingest", body));
    }

    @When("I submit an ingest request without a project name")
    public void submitIngestWithoutProjectName() {
        String body = """
                {"projectId":"test","repoUrl":"https://github.com/org/repo.git"}
                """;
        captureResponse(http.postJson("/api/v1/projects/ingest", body));
    }

    @When("I request the list of all projects")
    public void requestListProjects() {
        captureResponse(http.get("/api/v1/projects"));
    }

    @When("I request the ingestion status for project {string}")
    public void requestIngestionStatus(String projectId) {
        captureResponse(http.get("/api/v1/projects/" + projectId + "/status"));
    }

    @When("I request project {string} by ID")
    public void requestProjectById(String projectId) {
        captureResponse(http.get("/api/v1/projects/" + projectId));
    }

    private void captureResponse(BrainHttpResponse response) {
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @And("the response body contains project ID {string}")
    public void responseContainsProjectId(String projectId) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.get("projectId").asText()).isEqualTo(projectId);
    }

    @And("the response is an empty array")
    public void responseIsEmptyArray() throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isZero();
    }

    @And("the project list contains a project with ID {string}")
    public void projectListContainsId(String projectId) throws Exception {
        JsonNode array = objectMapper.readTree(state.getResponseBody());
        boolean found = false;
        for (JsonNode item : array) {
            if (projectId.equals(item.get("id").asText())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("Project list should contain ID " + projectId).isTrue();
    }

    @And("the project {string} has name {string}")
    public void projectHasName(String projectId, String expectedName) throws Exception {
        JsonNode array = objectMapper.readTree(state.getResponseBody());
        for (JsonNode item : array) {
            if (projectId.equals(item.get("id").asText())) {
                assertThat(item.get("name").asText()).isEqualTo(expectedName);
                return;
            }
        }
        assertThat(false).as("Project " + projectId + " not found in list").isTrue();
    }

    @And("the project list contains {int} projects")
    public void projectListContainsN(int count) throws Exception {
        JsonNode array = objectMapper.readTree(state.getResponseBody());
        assertThat(array.size()).isEqualTo(count);
    }
}
