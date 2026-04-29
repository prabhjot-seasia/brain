package com.assurant.brain.bdd.steps;

import com.assurant.brain.codegen.AstValidator;
import com.assurant.brain.codegen.ConventionChecker;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AstValidationSteps {

    private final Map<String, String> generatedFiles = new HashMap<>();
    private AstValidator.ValidationResult validationResult;
    private List<String> conventionViolations;

    @Given("generated code for {string} is:")
    public void generatedCodeIs(String path, String code) {
        generatedFiles.put(path, code);
    }

    @When("AST validation is performed on the generated code")
    public void astValidationPerformed() {
        AstValidator validator = new AstValidator();
        validationResult = validator.validate(generatedFiles);
    }

    @When("convention checking is performed on the generated code")
    public void conventionCheckingPerformed() {
        ConventionChecker checker = new ConventionChecker();
        conventionViolations = checker.check(generatedFiles);
    }

    @Then("the AST validation passes with no issues")
    public void astValidationPasses() {
        assertThat(validationResult.passed()).isTrue();
        assertThat(validationResult.issues()).isEmpty();
    }

    @Then("the AST validation fails with syntax errors")
    public void astValidationFails() {
        assertThat(validationResult.passed()).isFalse();
        assertThat(validationResult.issues()).isNotEmpty();
    }

    @Then("convention violations include {string}")
    public void conventionViolationsInclude(String violation) {
        assertThat(conventionViolations).isNotEmpty();
        assertThat(String.join(" ", conventionViolations).toLowerCase()).contains(violation.toLowerCase().replace("-", " "));
    }
}
