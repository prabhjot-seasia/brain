@embedded @wip
Feature: Semantic Cache
  As a platform operator
  I want similar LLM requests to return cached responses
  So that redundant LLM calls are eliminated

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Second identical request returns cached response
    Given the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-cache" with requirement "Add user authentication"
    Then the response status is 200
    When I submit an analyze request for project "proj-cache" with requirement "Add user authentication"
    Then the response status is 200
    And the second response was served from cache

  Scenario: Cache miss triggers LLM call
    Given the clarifier will respond as confident
    And the planner will generate a valid implementation plan
    When I submit an analyze request for project "proj-cache" with requirement "A completely unique requirement"
    Then the response status is 200
    And the response was not served from cache
