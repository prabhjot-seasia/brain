@embedded @wip
Feature: Avenger Review API
  As a platform using the Avengers protocol
  I want each Avenger callable as an MCP tool
  So that Claude Code can invoke domain-specific reviews directly

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: STARK reviews code and returns verdict without calling LLM
    When I POST to "/api/v1/avengers/STARK/review" with project "proj-1" and valid Java code
    Then the response status is 200
    And the response field "verdict" is "APPROVED"
    And no LLM call was made

  Scenario: STARK flags field injection as CHANGES_REQUESTED
    When I POST to "/api/v1/avengers/STARK/review" with project "proj-1" and code using @Autowired field
    Then the response status is 200
    And the response field "verdict" is "CHANGES_REQUESTED"

  Scenario: HAWKEYE reviews via LLM with persona prompt
    Given the Avenger LLM returns an APPROVED verdict
    When I POST to "/api/v1/avengers/HAWKEYE/review" with project "proj-1" and arbitrary code
    Then the response status is 200
    And the LLM was called with the HAWKEYE persona

  Scenario: full-review runs all 11 Avengers in parallel
    Given the Avenger LLM returns an APPROVED verdict
    When I POST to "/api/v1/avengers/full-review" with project "proj-1" and code
    Then the response status is 200
    And the response field "totalAvengers" is "11"
