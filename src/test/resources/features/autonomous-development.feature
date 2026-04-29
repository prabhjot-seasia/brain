@embedded
Feature: Autonomous Multi-Repo Development
  As a developer using Project Brain
  I want to feed a requirement and have Brain propose affected repos, generate plans, and draft PRs
  So that cross-repo changes are orchestrated autonomously with approval gates

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Start autonomous session with raw requirement
    When I start an autonomous development session with requirement "Add GSMA check to IMEI validation"
    Then the response status is 200
    And the response contains a "sessionId" field
    And the response contains a "intakeText" field

  Scenario: Clarify returns updated session state
    Given an autonomous session exists with pending clarification
    When I submit answers "Sync; 403 for blocked IMEIs" to the session
    Then the response status is 200
    And the response contains a "sessionId" field

  Scenario: Plan generates multi-repo umbrella JSON
    Given an autonomous session exists in CLARIFYING status
    When I request plan generation for the session
    Then the response status is 200
    And the response contains a "multiRepoPlan" field

  Scenario: Execute walks plan graph and returns per-project summary
    Given an autonomous session exists in PLANNED status with a final plan
    When I request execution for the session
    Then the response status is 200
    And the response contains a "perProject" field

  Scenario: Start rejects empty payload
    When I start an autonomous development session with no payload
    Then the response status is 400

  Scenario: Execute rejects session in wrong status
    Given an autonomous session exists in CLARIFYING status
    When I request execution for the session
    Then the response status is 409
