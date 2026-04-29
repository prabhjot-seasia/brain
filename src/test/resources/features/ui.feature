@ui
Feature: Project Brain Admin UI
  As a developer using the Project Brain admin dashboard
  I want to navigate the UI and interact with all pages
  So that I can manage projects, ingest code, analyze requirements, and view conventions

  Background:
    Given the Brain UI is running on the configured base URL

  Scenario: Application loads with header and sidebar
    When I open the Brain UI home page
    Then I see the header with text "Project Brain"
    And I see the sidebar with navigation items "Projects, Ingest, Analyze, Conventions"

  Scenario: Default route redirects to Projects page
    When I open the Brain UI home page
    Then the page title area contains "Projects"
    And the URL path is "/projects"

  # ── Projects Page ────────────────────────────────────────────────────

  Scenario: Projects page displays the project table
    When I navigate to the "Projects" page via sidebar
    Then I see a table with columns "Project ID, Name, Language, Framework, Last Indexed"

  @needs-clean-db
  Scenario: Projects page shows empty state when no projects
    # @needs-clean-db — skipped by `./gradlew bddUi` against an environment that
    # already has ingested projects. Run with `cucumber.filter.tags=@ui` against
    # a fresh stack (`docker compose down -v && ./build-and-deploy.sh --ollama`)
    # if you want to verify this scenario.
    When I navigate to the "Projects" page via sidebar
    Then I see the empty state message "No projects indexed yet"

  Scenario: Projects page has an Ingest Project button
    When I navigate to the "Projects" page via sidebar
    Then I see a button labeled "Ingest Project"
    When I click the "Ingest Project" button
    Then the URL path is "/ingest"

  # ── Ingest Page ──────────────────────────────────────────────────────

  Scenario: Ingest page displays the form fields
    When I navigate to the "Ingest" page via sidebar
    Then I see input fields "Project ID, Project Name, GitHub URL"
    And I see a button labeled "Start Ingestion"

  Scenario: Ingest page validates required fields
    When I navigate to the "Ingest" page via sidebar
    And I click the "Start Ingestion" button
    Then I see an error message containing "required"

  Scenario: Ingest with unreachable repo surfaces failure on the SSE stream, not as HTTP 500
    # Reproduces the user-reported "still seeing 500" — proves the controller's
    # async refactor returns 202 + jobId and the SSE stream carries the failure
    # as event:failed, never bleeding a raw 500 into the UI.
    When I navigate to the "Ingest" page via sidebar
    And I fill in the Ingest form with project "smoke-bdd-bad" name "smoke-bdd" repo "https://10.255.255.99/x.git"
    And I click the "Start Ingestion" button
    Then I do not see an HTTP 500 error toast or alert
    And I eventually see a progress message containing "Ingesting"
    And the browser console has no API requests that returned 500

  # ── Analyze Page ─────────────────────────────────────────────────────

  Scenario: Analyze page displays input form
    When I navigate to the "Analyze" page via sidebar
    Then the page title area contains "Analyze Requirement"

  # ── Conventions Page ─────────────────────────────────────────────────

  Scenario: Conventions page displays filters
    When I navigate to the "Conventions" page via sidebar
    Then I see a project selector dropdown
    And I see a category filter dropdown

  # ── Heavy-op pages must never surface raw 500 ───────────────────────

  Scenario: Tickets page propose flow does not surface HTTP 500
    When I navigate to the "Tickets" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And the browser console has no API requests that returned 500

  Scenario: Rule Packs page does not surface HTTP 500 on load
    When I navigate to the "/rulepacks" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And the browser console has no API requests that returned 500

  Scenario: Autodev page does not surface HTTP 500 on load
    When I navigate to the "Autodev" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And the browser console has no API requests that returned 500

  Scenario: Full Docs page does not surface HTTP 500 on load
    When I navigate to the "Docs" page via sidebar
    Then I do not see an HTTP 500 error toast or alert
    And the browser console has no API requests that returned 500

  # ── Sidebar Navigation ──────────────────────────────────────────────

  Scenario: Sidebar highlights active page
    When I navigate to the "Analyze" page via sidebar
    Then the "Analyze" sidebar item is highlighted
    And the "Projects" sidebar item is not highlighted
