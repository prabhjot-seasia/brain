@ui
Feature: Async-job lifecycle — every heavy operation surfaces progress + terminal events to the UI
  As a developer triggering heavy operations from the admin UI
  I want each operation to start with a 202 + jobId, stream progress, and finish with a terminal event
  So that no flow ever blocks the UI thread or surfaces a raw HTTP 500

  Background:
    Given the Brain UI is running on the configured base URL

  # ── C1 — Ingest kickoff is non-blocking and surfaces progress, never a 500 ─
  # UX guarantee only — the FE kicks off, sees progress, browser stays clean.
  # Server-side lifecycle (job actually reaches FAILED, SSE emits event:failed,
  # emitter completes, partial-unique-index dedup) is covered deterministically
  # in the @embedded JobControllerLifecycleIT (C8) where mocked services give
  # us sub-second timing instead of a 75-second TCP-connect timeout.
  Scenario: Ingest kickoff is non-blocking; UI shows progress, never a 500
    When I navigate to the "Ingest" page via sidebar
    And I fill in the Ingest form with project "lifecycle-c1" name "c1" repo "https://10.255.255.91/x.git"
    And I click the "Start Ingestion" button
    Then I do not see an HTTP 500 error toast or alert
    And I eventually see a progress message containing "Ingesting"
    And a progress bar is visible

  # ── C2 — Cross-page navigation during a job does not 500 ────────────
  # The cross-page snackbar's terminal-event delivery is also covered server-side
  # in the @embedded ITs; the actual snackbar wiring is in JobToastWatcher.test.tsx.
  Scenario: Navigating away from Ingest while a job is in flight produces no 500
    When I navigate to the "Ingest" page via sidebar
    And I fill in the Ingest form with project "lifecycle-c2" name "c2" repo "https://10.255.255.92/x.git"
    And I click the "Start Ingestion" button
    And I navigate to the "Projects" page via sidebar
    Then I do not see an HTTP 500 error toast or alert

  # ── C3 — Doc bundle page renders + no 500 ───────────────────────────
  Scenario: Docs page renders without 500
    When I navigate to the "Docs" page via sidebar
    Then I do not see an HTTP 500 error toast or alert

  # ── C4 — Autodev page renders the async pipeline shell ──────────────
  Scenario: Autodev page renders without 500 and exposes the Start kickoff
    When I navigate to the "Autodev" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And I see a button labeled "Start"

  # ── C5 — Tickets page renders + form is reachable ───────────────────
  Scenario: Tickets page renders without 500 and shows the propose form
    When I navigate to the "Tickets" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And I see input fields "Document content, Jira Project Key"

  # ── C7 — Rule Packs page renders ────────────────────────────────────
  Scenario: Rule Packs page renders without 500
    When I navigate to the "Rule Packs" page via sidebar
    Then I do not see an HTTP 500 error toast or alert

  # ── C14 — Browser-console clean across remaining pages ──────────────
  Scenario Outline: Page <page> has no 500 in browser console
    When I navigate to the "<page>" page via sidebar
    Then I do not see an HTTP 500 error toast or alert

    Examples:
      | page         |
      | Projects     |
      | Conventions  |
      | Analyze      |
      | Learning     |
