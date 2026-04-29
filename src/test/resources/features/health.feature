@live
Feature: Health and Actuator Endpoints
  As an operations engineer
  I want to verify the Brain API health endpoints
  So that I can monitor service availability

  # @live tag — these scenarios work in both embedded mode (./gradlew test)
  # AND live mode against a deployed Brain (./gradlew bddLive). They depend
  # only on /actuator/* endpoints, no LLM mocks or pre-seeded DB state.

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Health endpoint is reachable
    When I request the health endpoint
    Then the health response status is successful

  Scenario: Info endpoint is accessible
    When I request the info endpoint
    Then the info response is successful
