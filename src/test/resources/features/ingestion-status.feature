Feature: Ingestion Status Tracking
  As a developer using Project Brain
  I want to see the status and error details of ingestion
  So that I know if ingestion succeeded or failed and why

  Background:
    Given the Brain API is running with mocked LLM services

  @embedded
  Scenario: Get ingestion status for a project
    Given a project "proj-alpha" with name "Alpha Service" exists in the database
    When I request the ingestion status for project "proj-alpha"
    Then the response status is 200
    And the response field "projectId" is "proj-alpha"
    And the response field "status" is "PENDING"
    And the response field "error" is ""

  @live
  Scenario: Get status for non-existent project returns 404
    When I request the ingestion status for project "does-not-exist"
    Then the response status is 404
