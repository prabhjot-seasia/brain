@embedded
Feature: Document Generation
  As a developer using Project Brain
  I want to generate architecture documents from my codebase
  So that I have up-to-date documentation

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Generate returns 202 accepted for async processing
    Given the doc generator will produce markdown content
    When I request doc generation for project "proj-docs" with prompt "Explain the authentication flow" and type "EXPLANATION"
    Then the response status is 202
    And the response contains a "id" field
    And the response field "status" is "PENDING"
    And the response field "docType" is "EXPLANATION"

  Scenario: Generate flow diagram returns 202
    Given the doc generator will produce markdown content
    When I request doc generation for project "proj-docs" with prompt "Show the ingestion flow" and type "FLOW_DIAGRAM"
    Then the response status is 202
    And the response field "docType" is "FLOW_DIAGRAM"

  Scenario: Invalid doc type returns 400
    When I request doc generation for project "proj-docs" with prompt "Something" and type "INVALID"
    Then the response status is 400
    And the response contains an "error" field

  Scenario: Missing projectId returns 400
    When I request doc generation without projectId
    Then the response status is 400
