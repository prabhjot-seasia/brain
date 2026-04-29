@embedded @wip
Feature: Token Budget Enforcement
  As a platform operator
  I want LLM calls to respect token budgets
  So that costs stay predictable and context windows are not exceeded

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: RAG context is pruned to configured token budget
    Given a project "proj-budget" is ingested with 20 code chunks
    And the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-budget" with requirement "Add payment retry logic"
    Then the response status is 200
    And the RAG context used in the plan was within the token budget

  Scenario: Convention list is pruned to top 10
    Given a project "proj-conventions" has 25 conventions
    And the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-conventions" with requirement "Refactor service layer"
    Then the response status is 200
    And at most 10 conventions were injected into the prompt
