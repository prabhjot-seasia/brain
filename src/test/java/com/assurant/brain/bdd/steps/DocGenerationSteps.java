package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.assurant.brain.docs.DocGeneratorService;
import com.assurant.brain.enums.DocType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class DocGenerationSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private SharedTestState state;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private DocGeneratorService docGeneratorService;

    @Given("the doc generator will produce markdown content")
    public void docGeneratorProducesMarkdown() {
        when(docGeneratorService.generate(anyString(), anyString(), any(DocType.class)))
                .thenReturn("## Generated Document\nThis is generated content.");
    }

    @When("I request doc generation for project {string} with prompt {string} and type {string}")
    public void requestDocGeneration(String projectId, String prompt, String type) {
        String body = """
                { "projectId": "%s", "prompt": "%s", "type": "%s" }
                """.formatted(projectId, prompt, type);
        BrainHttpResponse response = http.postJson("/api/v1/docs/generate", body);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @When("I request doc generation without projectId")
    public void requestDocGenerationWithoutProjectId() {
        String body = """
                { "prompt": "Something" }
                """;
        BrainHttpResponse response = http.postJson("/api/v1/docs/generate", body);
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @And("the response contains a {string} field")
    public void responseContainsField(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.has(field)).isTrue();
    }

    @And("the response contains an {string} field")
    public void responseContainsAnField(String field) throws Exception {
        JsonNode node = objectMapper.readTree(state.getResponseBody());
        assertThat(node.has(field)).isTrue();
    }
}
