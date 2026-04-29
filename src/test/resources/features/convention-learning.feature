@embedded @wip
Feature: Convention Learning from Merged PRs
  As a learning platform
  I want to adjust convention trust weights from merged PR feedback
  So that future code generation improves over time

  Background:
    Given the Brain API is running with mocked LLM services

  Scenario: Convention weights are adjusted after merge analysis
    Given a PR record exists with generated files and a merge diff
    When the merged PR is analyzed for convention adherence
    Then followed conventions have their trust weight increased
    And violated conventions have their trust weight decreased

  Scenario: Adaptive prompts strengthen frequently violated conventions
    Given a convention "no-field-injection" has been violated 3 times
    When adaptive prompt building is triggered for the project
    Then the generated prompt contains explicit enforcement for "no-field-injection"
