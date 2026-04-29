package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

public class HealthSteps {

    @Autowired private BrainHttpClient http;
    @Autowired private SharedTestState state;

    @When("I request the health endpoint")
    public void requestHealth() {
        BrainHttpResponse response = http.get("/actuator/health");
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @When("I request the info endpoint")
    public void requestInfo() {
        BrainHttpResponse response = http.get("/actuator/info");
        state.setResponseStatus(response.status());
        state.setResponseBody(response.body());
    }

    @Then("the health response status is successful")
    public void healthResponseIsSuccessful() {
        assertThat(state.getResponseStatus()).isIn(200, 503);
        assertThat(state.getResponseBody()).contains("status");
    }

    @Then("the info response is successful")
    public void infoResponseIsSuccessful() {
        assertThat(state.getResponseStatus()).isEqualTo(200);
    }
}
