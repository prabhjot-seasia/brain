Feature: Project Ingestion API
  As a developer using Project Brain
  I want to ingest a project from a GitHub repository
  So that the Brain can index and understand my codebase

  Background:
    Given the Brain API is running with mocked LLM services

  @live
  Scenario: Successfully ingest a project from GitHub
    When I submit an ingest request for project "test-project" with name "Test Project" and repo "https://github.com/test/repo.git"
    Then the response status is 202
    And the response body contains "INGEST_PROJECT"
    And the response body contains project ID "test-project"

  @live
  Scenario: Re-ingesting a project is idempotent
    When I submit an ingest request for project "test-project" with name "Test Project" and repo "https://github.com/test/repo.git"
    Then the response status is 202
    When I submit an ingest request for project "test-project" with name "Test Project" and repo "https://github.com/test/repo.git"
    Then the response status is 202

  @embedded
  Scenario: List projects when none exist
    When I request the list of all projects
    Then the response status is 200
    And the response is an empty array

  @embedded
  Scenario: List projects after ingestion
    Given a project "proj-alpha" with name "Alpha Service" exists in the database
    When I request the list of all projects
    Then the response status is 200
    And the project list contains a project with ID "proj-alpha"
    And the project "proj-alpha" has name "Alpha Service"

  @embedded
  Scenario: List multiple projects
    Given a project "proj-alpha" with name "Alpha Service" exists in the database
    And a project "proj-beta" with name "Beta Service" exists in the database
    When I request the list of all projects
    Then the response status is 200
    And the project list contains 2 projects

  @embedded
  Scenario: Get project by ID
    Given a project "proj-alpha" with name "Alpha Service" exists in the database
    When I request project "proj-alpha" by ID
    Then the response status is 200
    And the response field "id" is "proj-alpha"
    And the response field "name" is "Alpha Service"

  @live
  Scenario: Get non-existent project returns 404
    When I request project "does-not-exist" by ID
    Then the response status is 404

  @live
  Scenario: Ingest without repo URL returns 400
    When I submit an ingest request without a repo URL
    Then the response status is 400

  @live
  Scenario: Ingest without project name returns 400
    When I submit an ingest request without a project name
    Then the response status is 400
