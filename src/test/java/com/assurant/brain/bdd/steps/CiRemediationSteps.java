package com.assurant.brain.bdd.steps;

import com.assurant.brain.bdd.http.BrainHttpClient;
import com.assurant.brain.bdd.http.BrainHttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

public class CiRemediationSteps {

    private static final String TEST_SECRET = "test-webhook-secret";

    @Autowired private BrainHttpClient http;
    @Autowired private ObjectMapper objectMapper;

    private int webhookStatus;
    private String webhookBody;

    @Given("a valid GitHub webhook secret is configured")
    public void webhookSecretConfigured() {
    }

    @When("a workflow_run webhook arrives with a valid HMAC signature and action {string}")
    public void webhookWithValidSignature(String action) throws Exception {
        String payload = """
                {"action":"%s","workflow_run":{"id":1,"conclusion":"success","head_branch":"brain/test"}}
                """.formatted(action);
        sendWebhook(payload, computeSignature(payload), "workflow_run");
    }

    @When("a workflow_run webhook arrives with an invalid HMAC signature")
    public void webhookWithInvalidSignature() {
        String payload = """
                {"action":"completed","workflow_run":{"id":1,"conclusion":"failure","head_branch":"brain/test"}}
                """;
        sendWebhook(payload, "sha256=invalid", "workflow_run");
    }

    @When("a workflow_run webhook arrives for branch {string} with conclusion {string}")
    public void webhookForBranch(String branch, String conclusion) throws Exception {
        String payload = """
                {"action":"completed","workflow_run":{"id":2,"conclusion":"%s","head_branch":"%s"}}
                """.formatted(conclusion, branch);
        sendWebhook(payload, computeSignature(payload), "workflow_run");
    }

    @Then("the webhook response status is {int}")
    public void webhookResponseStatusIs(int expected) {
        assertThat(webhookStatus).isEqualTo(expected);
    }

    @And("the webhook response indicates the event was ignored")
    public void webhookResponseIgnored() throws Exception {
        JsonNode node = objectMapper.readTree(webhookBody);
        assertThat(node.has("reason")).isTrue();
    }

    private void sendWebhook(String payload, String signature, String event) {
        BrainHttpResponse response = http.postJson("/api/v1/webhooks/github", payload);
        webhookStatus = response.status();
        webhookBody = response.body();
    }

    private String computeSignature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hash);
    }
}
