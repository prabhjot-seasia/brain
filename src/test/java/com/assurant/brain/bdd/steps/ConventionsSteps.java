package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class ConventionsSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SharedTestState state;
    @Autowired private ConventionNodeRepository conventionNodeRepository;

    @Given("the convention repository has conventions for project {string}:")
    public void conventionRepoHasConventions(String projectId, DataTable table) {
        List<Map<String, String>> rows = table.asMaps();
        List<ConventionNode> allConventions = new ArrayList<>();

        long idCounter = 1;
        for (Map<String, String> row : rows) {
            ConventionNode node = new ConventionNode();
            node.setId(idCounter++);
            node.setRule(row.get("rule"));
            node.setCategory(row.get("category"));
            node.setSourceFile(row.get("sourceFile"));
            node.setTrustWeight(Double.parseDouble(row.get("trustWeight")));
            node.setProjectId(projectId);
            allConventions.add(node);
        }

        List<ConventionNode> sortedByTrust = new ArrayList<>(allConventions);
        sortedByTrust.sort((a, b) -> Double.compare(b.getTrustWeight(), a.getTrustWeight()));

        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(eq(projectId)))
                .thenReturn(sortedByTrust);

        for (ConventionNode c : allConventions) {
            String cat = c.getCategory();
            List<ConventionNode> filtered = allConventions.stream()
                    .filter(n -> n.getCategory().equals(cat))
                    .toList();
            when(conventionNodeRepository.findByProjectIdAndCategory(eq(projectId), eq(cat)))
                    .thenReturn(filtered);
        }
    }

    @Given("the convention repository has no conventions for project {string}")
    public void conventionRepoEmpty(String projectId) {
        when(conventionNodeRepository.findByProjectIdOrderByTrustWeightDesc(eq(projectId)))
                .thenReturn(Collections.emptyList());
    }

    @When("I request conventions for project {string}")
    public void requestConventions(String projectId) {
        BrainHttpResponse response = http.get("/api/v1/projects/" + projectId + "/conventions");
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @When("I request conventions for project {string} with category {string}")
    public void requestConventionsWithCategory(String projectId, String category) {
        BrainHttpResponse response = http.get(
                "/api/v1/projects/" + projectId + "/conventions",
                Map.of("category", category));
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @And("the conventions list contains {int} entries")
    public void conventionsListSize(int expected) throws Exception {
        JsonNode array = objectMapper.readTree(state.getResponseBody());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(expected);
    }

    @And("convention {int} rule is {string}")
    public void conventionRuleIs(int index, String expectedRule) throws Exception {
        JsonNode array = objectMapper.readTree(state.getResponseBody());
        assertThat(array.get(index - 1).get("rule").asText()).isEqualTo(expectedRule);
    }
}
