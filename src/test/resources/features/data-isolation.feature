@embedded @wip
Feature: Data Isolation Between Projects
  As a multi-tenant platform
  I want project data to be isolated
  So that one project cannot access another project's data

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Conventions are scoped to their project
    Given a project "proj-alpha" exists with convention "Use camelCase"
    And a project "proj-beta" exists with convention "Use snake_case"
    When I request conventions for project "proj-alpha"
    Then the response status is 200
    And the conventions contain "Use camelCase"
    And the conventions do not contain "Use snake_case"

  Scenario: Documents are scoped to their project
    When I request documents for project "proj-alpha"
    Then the response status is 200
    And only documents belonging to "proj-alpha" are returned
