@embedded @wip
Feature: Pull Request Creation
  As a developer using Project Brain
  I want the system to create GitHub PRs from implementation plans
  So that code changes are delivered automatically

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: PR creation requires a valid session
    When I request PR creation without a session ID
    Then the response status is 400

  Scenario: PR creation returns status tracking
    Given a clarification session "session-pr-1" exists with a final plan
    When I request PR creation for session "session-pr-1"
    Then the response status is 200
    And the response contains a "prRecordId" field
