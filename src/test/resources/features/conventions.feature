@embedded
Feature: Conventions API
  As a developer using Project Brain
  I want to view extracted coding conventions for a project
  So that I can understand and follow established patterns

  # @embedded — uses @MockitoBean ConventionNodeRepository to seed graph data
  # in-process. NOT runnable against a live deployment without first ingesting
  # real projects whose Neo4j conventions match the expected fixture data.

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Get all conventions for a project
    Given the convention repository has conventions for project "proj-alpha":
      | rule                        | category        | sourceFile       | trustWeight |
      | Use constructor injection   | dependency-mgmt | CONTRIBUTING.md  | 1.5         |
      | Log after commit            | logging         | inferred         | 1.0         |
      | Use StringUtils.isNotBlank  | string-handling | code-style.md    | 1.5         |
    When I request conventions for project "proj-alpha"
    Then the response status is 200
    And the conventions list contains 3 entries

  Scenario: Filter conventions by category
    Given the convention repository has conventions for project "proj-alpha":
      | rule                        | category        | sourceFile       | trustWeight |
      | Use constructor injection   | dependency-mgmt | CONTRIBUTING.md  | 1.5         |
      | Log after commit            | logging         | inferred         | 1.0         |
    When I request conventions for project "proj-alpha" with category "logging"
    Then the response status is 200
    And the conventions list contains 1 entries
    And convention 1 rule is "Log after commit"

  Scenario: Get conventions for project with none returns empty list
    Given the convention repository has no conventions for project "proj-empty"
    When I request conventions for project "proj-empty"
    Then the response status is 200
    And the conventions list contains 0 entries

  Scenario: Conventions are ordered by trust weight descending
    Given the convention repository has conventions for project "proj-alpha":
      | rule            | category | sourceFile  | trustWeight |
      | Low trust rule  | logging  | inferred    | 0.8         |
      | High trust rule | logging  | README.md   | 1.5         |
      | Mid trust rule  | logging  | inferred    | 1.0         |
    When I request conventions for project "proj-alpha"
    Then the response status is 200
    And convention 1 rule is "High trust rule"
