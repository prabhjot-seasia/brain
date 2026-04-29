@embedded
Feature: Requirement Analysis API
  As a developer using Project Brain
  I want to analyze requirements through a clarification loop
  So that I receive precise, convention-aware implementation plans

  # @embedded — these scenarios stub ClarifierService and PlannerService via
  # @MockitoBean and verify session state via direct JdbcTemplate queries.
  # That only works inside the Spring Boot test context, so they are NOT
  # included in the @live suite. To exercise the analyze flow against a real
  # deployment, ingest a project and call POST /api/v1/analyze manually.

  Background:
    Given the Brain API is running with mocked LLM services

  # ── Clarification Flow ──────────────────────────────────────────────

  Scenario: Vague requirement triggers clarifying questions
    Given the clarifier will respond as not confident with questions:
      | What is the business reason for adding logging? |
      | Which service or module should be updated?      |
    When I submit an analyze request for project "proj-payments" with requirement "Add some logging"
    Then the response status is 200
    And the response field "planReady" is false
    And the response contains a non-empty "sessionId"
    And the response contains 2 questions
    And the response question 1 is "What is the business reason for adding logging?"
    And the response question 2 is "Which service or module should be updated?"

  Scenario: Developer answers clarifying questions and receives a plan
    Given the clarifier will respond as not confident with questions:
      | Which service handles payments? |
    When I submit an analyze request for project "proj-payments" with requirement "Add audit logging to payment flow"
    Then the response status is 200
    And the response field "planReady" is false
    And I capture the "sessionId" from the response
    Given the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-payments" with requirement "Add audit logging to payment flow" using the captured session and answers "PaymentService in payments-core. Compliance team requires it."
    Then the response status is 200
    And the response field "planReady" is true
    And the response contains a non-empty "plan"
    And the session is marked as "COMPLETE" in the database

  Scenario: Unknown references are flagged in clarification response
    Given the clarifier will respond as not confident with unknown references:
      | legacy-billing-v1 |
      | kafka-bridge       |
    When I submit an analyze request for project "proj-payments" with requirement "Connect to legacy-billing-v1 via kafka-bridge"
    Then the response status is 200
    And the response field "planReady" is false
    And the response contains 2 unknown references
    And the response unknown reference 1 is "legacy-billing-v1"

  Scenario: Multi-round clarification converges to a plan
    Given the clarifier will respond as not confident with questions:
      | What is the business driver? |
    When I submit an analyze request for project "proj-payments" with requirement "Refactor the payment module"
    Then the response status is 200
    And I capture the "sessionId" from the response
    Given the clarifier will respond as not confident with questions:
      | Which classes specifically need refactoring? |
    When I submit an analyze request for project "proj-payments" with requirement "Refactor the payment module" using the captured session and answers "Tech debt reduction"
    Then the response status is 200
    And the response contains 1 questions
    Given the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-payments" with requirement "Refactor the payment module" using the captured session and answers "PaymentService and PaymentValidator"
    Then the response status is 200
    And the response field "planReady" is true

  # ── Validation & Error Handling ──────────────────────────────────────

  Scenario: Missing projectId returns 400
    When I submit an analyze request with missing projectId
    Then the response status is 400

  Scenario: Missing requirement returns 400
    When I submit an analyze request with missing requirement
    Then the response status is 400

  Scenario: Invalid session ID returns 404
    When I submit an analyze request for project "proj-payments" with requirement "Test" and invalid session "00000000-0000-0000-0000-000000000099"
    Then the response status is 404

  Scenario: Blank requirement returns 400
    When I submit an analyze request for project "proj-payments" with blank requirement
    Then the response status is 400
