@embedded @wip
Feature: CI Remediation
  As a code generation platform
  I want failed CI runs to trigger automatic remediation
  So that generated PRs are self-healing

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Webhook with valid signature is accepted
    Given a valid GitHub webhook secret is configured
    When a workflow_run webhook arrives with a valid HMAC signature and action "completed"
    Then the webhook response status is 200

  Scenario: Webhook with invalid signature is rejected
    When a workflow_run webhook arrives with an invalid HMAC signature
    Then the webhook response status is 401

  Scenario: Non-Brain branch is ignored
    Given a valid GitHub webhook secret is configured
    When a workflow_run webhook arrives for branch "feature/unrelated" with conclusion "failure"
    Then the webhook response status is 200
    And the webhook response indicates the event was ignored
