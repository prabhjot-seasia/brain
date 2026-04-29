@embedded @wip
Feature: Avenger Memory
  As a learning platform
  I want each Avenger to remember patterns across sessions
  So that repeat offenders get stricter prompts next time

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: HAWKEYE learns from repeated violations
    Given HAWKEYE has observed "no-field-injection" violated 3 times on project "proj-1"
    When HAWKEYE reviews new code for project "proj-1"
    Then the persona prompt contains a HAWKEYE MEMORY section
    And the memory section mentions "no-field-injection"

  Scenario: memory is scoped per Avenger
    Given HAWKEYE has observed "pii-leak" on project "proj-1"
    And STARK has no observations on project "proj-1"
    When STARK reviews code for project "proj-1"
    Then the persona prompt does not contain a HAWKEYE memory hint

  Scenario: cache hit returns memory without querying DB
    Given an AvengerMemory snapshot is cached in Redis for HAWKEYE on "proj-1"
    When HAWKEYE reviews code for project "proj-1"
    Then the Redis cache is hit
    And the database is not queried
