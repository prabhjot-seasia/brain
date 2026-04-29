# Testing

> **Who this document is for:** Developers and QA engineers who need to run, understand, or write tests for Project Brain. Written for someone new to the project's testing setup — you should know what unit tests and integration tests are, but you don't need prior experience with TestContainers, Cucumber, or Selenium.

---

## Test Coverage Summary

| Layer | Coverage | Threshold | Tool |
|-------|----------|-----------|------|
| Backend (Java) | 93% instructions, 76% branches | 90% instructions, 70% branches | JaCoCo |
| Frontend (React) | 99% statements, 90% branches | 90% statements, 85% branches | Vitest v8 |

Coverage is **enforced in CI** — builds fail if coverage drops below the thresholds.

---

## Running Backend Tests

### Full Suite (Recommended)

```bash
# Requires Docker (TestContainers spins up a PostgreSQL container automatically)
./gradlew test
```

This runs ~560 tests including unit tests, integration tests, and BDD scenarios. Takes 4-6 minutes.

> **Test count breakdown:** ~415 JUnit `@Test` methods (unit + integration) + Cucumber scenarios (counted by both the standalone Cucumber runner and `CucumberSuiteIT`) + parameterized test expansions. The 560 figure is the aggregate Gradle reports — the same behavior you see running `./gradlew test`.

**You don't need a running database.** TestContainers starts a `pgvector/pgvector:pg16` Docker container automatically for each test run and tears it down after. Neo4j and all LLM calls are mocked.

**Sandbox tests (Wave A2)**: `SandboxValidationServiceTest` exercises pure helpers (build-tool detection, command builders, env hardening, Docker wrap). It does **not** spawn real subprocess builds. Real sandbox runs require `BRAIN_SANDBOX_ENABLED=true` and (for full isolation) `BRAIN_SANDBOX_DOCKER_IMAGE=<image>` — these run only when explicitly enabled at deploy time, never in CI.

### With Coverage Report

```bash
./gradlew test jacocoTestReport
# Open the HTML report:
open build/reports/jacoco/test/html/index.html
```

### Single Test Class

```bash
./gradlew test --tests "com.assurant.brain.service.PlannerServiceTest"
```

### Single Test Method

```bash
./gradlew test --tests "com.assurant.brain.service.PlannerServiceTest.generatePlanHappyPath"
```

### Coverage Verification (CI Gate)

```bash
./gradlew check
# This runs: test → jacocoTestReport → jacocoTestCoverageVerification
# Fails if instruction coverage < 90% or branch coverage < 70%
```

---

## Running Frontend Tests

### Full Suite

```bash
cd ui
npx vitest run
```

Runs 91 tests across 15 test files. Takes ~17 seconds.

### With Coverage

```bash
cd ui
npx vitest run --coverage
```

Coverage report prints to terminal and generates HTML at `ui/coverage/index.html`.

### Watch Mode (During Development)

```bash
cd ui
npx vitest
# Re-runs tests automatically when you save a file
```

### Single Test File

```bash
cd ui
npx vitest run src/pages/analyze/AnalyzePage.test.tsx
```

---

## Test Architecture

### Backend Test Types

The backend has three categories of tests:

#### 1. Unit Tests (Fast, No Spring Context)

These test a single class in isolation. Dependencies are mocked with Mockito. No Spring Boot context starts. No Docker.

**Location:** `src/test/java/com/assurant/brain/service/` and `src/test/java/com/assurant/brain/facade/`

**Examples:**
- `PlannerServiceTest` — mocks ChatModel, VectorStore, repositories; verifies prompt construction and RAG retrieval
- `ClarifierServiceTest` — tests the server-side confidence guard with various dimension score combinations
- `ProjectDetectorTest` — creates temp directories with marker files, verifies auto-detection
- `IngestionServiceParsingTest` — tests Java/POM/doc file parsing, error message resolution, chunk splitting

**How to recognize:** No `@SpringBootTest`, no `extends BrainApplicationTests`. Uses `mock()` directly.

#### 2. Integration Tests (Spring Context + Real Database)

These start a full Spring Boot context with a real PostgreSQL database (via TestContainers). The LLM and Neo4j are mocked. HTTP requests go through MockMvc.

**Location:** `src/test/java/com/assurant/brain/rest/v1/`

**Examples:**
- `IngestControllerIT` — tests all ingest endpoints (202 for valid, 400 for missing fields, project CRUD)
- `AnalyzeFlowIT` — tests the full clarification → plan flow through the API
- `ConventionsControllerIT` — tests convention listing with category filters

**How to recognize:** Extends `BrainApplicationTests`, has `@MockitoBean` for services, uses `mvc.perform(...)`.

#### 3. BDD Tests (Cucumber + Gherkin)

Business-readable scenarios written in `.feature` files. Three execution modes:

| Mode | Command | What It Tests | How It Reaches the API |
|------|---------|---------------|----------------------|
| Embedded | `./gradlew test` | Full suite with mocked LLM | `MockMvcBrainHttpClient` (in-process) |
| Live API | `./gradlew bddLive` | `@live`-tagged scenarios | `RestTemplateBrainHttpClient` (real HTTP) |
| Live UI | `./gradlew bddUi` | `@ui`-tagged scenarios | Selenium (real Chrome browser) |

**Feature files:** `src/test/resources/features/`

**Step definitions:** `src/test/java/com/assurant/brain/bdd/steps/`

**The key abstraction:** `BrainHttpClient` interface — has two implementations (MockMvc for embedded tests, RestTemplate for live tests). Step definitions call `brainHttpClient.ingest(...)` without knowing which implementation is active.

### Frontend Test Approach

Every React component has a co-located `.test.tsx` file:

```
src/pages/analyze/
├── AnalyzePage.tsx
└── AnalyzePage.test.tsx       # 11 tests
```

Tests use:
- **Vitest** — test runner (fast, Vite-native)
- **@testing-library/react** — renders components and queries the DOM
- **@testing-library/user-event** — simulates user interactions (clicks, typing)
- **vi.mock()** — mocks the API client to avoid real HTTP calls

**Pattern:** Mock `brainApi` methods → render the component → simulate user actions → assert on DOM state.

---

## Writing New Tests

### Backend Unit Test Template

```java
package com.assurant.brain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("MyService")
class MyServiceTest {

    private MyDependency dependency;
    private MyService service;

    @BeforeEach
    void setup() {
        dependency = mock(MyDependency.class);
        service = new MyService(dependency);
    }

    @Test
    @DisplayName("doThing returns expected result")
    void doThingHappyPath() {
        when(dependency.fetch("x")).thenReturn("y");
        String result = service.doThing("x");
        assertThat(result).isEqualTo("y");
    }
}
```

### Frontend Test Template

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import MyPage from './MyPage'

const mockMyApi = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    myMethod: (...args: unknown[]) => mockMyApi(...args),
  },
}))

describe('MyPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the heading', async () => {
    mockMyApi.mockResolvedValue([])
    render(<MyPage />)
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /My Page/i })).toBeInTheDocument()
    })
  })
})
```

---

## BDD: Running Against a Live Environment

### Live API Tests

```bash
# Against local Brain API
./gradlew bddLive

# Against deployed environment
./gradlew bddLive -Ptarget=https://dev.hyla.hylatest.com/project-brain-backend
```

### Live UI Tests (Selenium)

Prerequisites: Chrome installed, Brain API running, UI running.

```bash
# Start the full stack first
./build-and-deploy.sh --ollama

# Then run UI tests (opens a real Chrome window)
./gradlew bddUi

# Against a deployed UI
./gradlew bddUi -PuiTarget=https://dev-brain-ui.cs.dls-test.com
```

The UI tests run in a **real Chrome browser** (not headless). WebDriverManager downloads the matching ChromeDriver automatically.

---

## What Each Test File Covers

### Backend

| Test File | What It Tests | # Tests |
|-----------|--------------|---------|
| `PlannerServiceTest` | RAG retrieval, prompt construction, clarification formatting, conventions formatting | 7 |
| `ClarifierServiceTest` | Server-side confidence guard (all threshold combinations) | 6 |
| `ClarifierServiceFullTest` | Full analyze() flow: confident/not-confident, markdown stripping, malformed JSON | 7 |
| `GitCloneServiceTest` | Error message resolution (404, auth, timeout, unknown) | 6 |
| `GitCloneServiceFullTest` | Credential provider (PAT/empty/null), clone failures, CloneResult record | 10 |
| `ProjectDetectorTest` | All auto-detection rules (Java/Gradle, Java/Maven, TypeScript/npm, etc.) | 12 |
| `IngestionServiceIncrementalTest` | Full vs incremental ingestion, skip unchanged, embed modified, delete removed | 6 |
| `IngestionServiceParsingTest` | Java parsing (interface/enum), POM parsing, doc parsing, error messages, chunk splitting | 23 |
| `AnalysisFacadeIntentTest` | Intent classification (EXPLAIN vs IMPLEMENT) | 18 |
| `AnalysisFacadeTest` | Full analyze flow: clarify, plan, force-promote, answers, truncated JSON | 10 |
| `EmbeddingHealthIndicatorTest` | Health indicator UP/DOWN | 2 |
| `EmbeddingWarmupRunnerTest` | Warmup runner success/failure | 2 |
| `GlobalExceptionHandlerTest` | Exception → HTTP status mapping | 6 |
| `IngestControllerIT` | All ingest/project endpoints via MockMvc | 9 |
| `AnalyzeFlowIT` | Full clarification → plan flow via MockMvc | 3+ |
| `ConventionsControllerIT` | Convention listing + filtering | 2+ |
| `TokenEstimatorTest` | Token estimation heuristic, truncation, budget checks | 5 |
| `ContextWindowManagerTest` | Context pruning, DOC trust weighting, budget enforcement | 6 |
| `SemanticCacheServiceTest` | Redis cache get/put, cache disabled mode, TTL | 4 |
| `TokenUsageTrackerTest` | Token tracking, cost calculation, cache hit recording, exception swallowing | 4 |
| `AstValidatorTest` | Java AST parsing, naming conventions, comment detection | 5 |
| `ConventionCheckerTest` | @RequiredArgsConstructor, @Autowired field injection, @Slf4j, enum status | 5 |
| `HallucinationDetectorTest` | Import cross-referencing against project graph | 4 |
| `DiffGeneratorTest` | Small change detection, unified diff generation | 3 |
| `DiffApplierTest` | Diff hunk parsing and application | 3 |
| `AdaptivePromptBuilderTest` | Convention violation tracking, prompt strengthening | 4 |
| `SolutionPatternServiceTest` | Pattern recording, few-shot retrieval, word overlap matching | 4 |
| `StructuralConventionAnalyzerTest` | AST-level convention comparison (generated vs merged) | 3 |
| `CodeReviewServiceTest` | AST validation before LLM, convention check, markdown fences, invalid JSON | 4 |
| `CodeGeneratorServiceTest` | Code generation with RAG context, convention pruning | 3 |
| `CiRemediationServiceTest` | CI failure remediation, max attempts, commit push | 4 |
| `CiFailureParserTest` | Log parsing, failure extraction | 3 |
| `JwtTokenProviderTest` | Token round-trip, tampered token, secret fallback | 6 |
| `SecurityUtilsTest` | Authenticated user, anonymous fallback | 3 |
| `AuthControllerTest` | Login, refresh, missing userId | 4 |
| `JiraClientTest` | No session, expired session, no cloud ID | 3 |
| `TicketCreationServiceTest` | Ticket creation success/failure/mixed | 4 |
| `GitHubWebhookControllerTest` | HMAC verification, event filtering, remediation trigger | 7 |
| `ImageAnalyzerServiceTest` | Image analysis, default content type, exception wrapping | 3 |
| `MergedPrAnalyzerServiceTest` | PR not found, no files, convention analysis, malformed JSON | 4 |
| `DocGeneratorServiceTest` | Cache hit/miss, all doc types | 3 |
| `DocGeneratorControllerTest` | Async 202 response, status polling, validation, CRUD | 10 |
| `JiraTicketControllerTest` | Async propose, create, validation | 5 |
| `JiraOAuthControllerTest` | Connect URL, status connected/expired/missing | 4 |
| `CorrelationIdFilterTest` | ID generation, propagation, sanitization, ThreadContext cleanup | 4 |
| `BrainMetricsTest` | Micrometer counters/timers registration and increment | 5 |
| `ApiVersionFilterTest` | X-API-Version header on responses | 1 |
| `RailChainTest` | Priority ordering, short-circuit on BLOCK, MODIFY propagation, pre/post separation | 5 |
| `InputLengthRailTest` | Within limit / over limit / null input | 3 |
| `PromptInjectionRailTest` | Classifier BLOCK, PASS, blank input | 3 |
| `PiiMaskRailTest` | SSN / email+phone / JWT masking, clean text, block mode | 5 |
| `OutputSchemaRailTest` | No schema / matching / violating / fenced JSON | 4 |
| `OutputPiiRailTest` | SSN leak blocked / clean / null | 3 |
| `OutputLengthRailTest` | Within limit / over limit | 2 |
| `RegexInjectionClassifierTest` | Pattern detection: ignore-previous, system-role, jailbreak, benign, blank | 5 |
| `BrainMetricsGuardrailTest` | Tagged counter per rail + decision | 1 |
| `AvengerTypeTest` | 11 Avengers, role distribution, distinct domains, persona paths | 4 |
| `AvengerPersonaLoaderTest` | Loads all 11 personas from classpath + Output Contract section | 3 |
| `AvengerReviewerTest` | STARK delegates, @Autowired flagged, LLM path, malformed response, LearningEvent emission, invalidate-on-write | 7 |
| `AvengerOrchestratorTest` | Parallel execution, BLOCKED propagation, CHANGES_REQUESTED aggregation | 3 |
| `AvengerControllerTest` | Review delegate, full-review delegate, history endpoint | 3 |
| `ConventionKeyExtractorTest` | 8 normalization rules + truncation | 7 |
| `AvengerMemoryTest` | Empty snapshot / event rebuild / cache hit / invalidate / Redis failure | 5 |
| `BrainMetricsAvengerMemoryTest` | Tagged hits/misses per Avenger | 1 |

### BDD Feature Files

Features tagged `@wip` are aspirational — the scenario captures intended behavior but step defs are not yet implemented. They are excluded from `./gradlew test` via `junit-platform.properties`. See [WIP Features](WIP_FEATURES.md) for the rationale and how to implement one.

| Feature | Tag | Scenarios |
|---------|-----|-----------|
| `health.feature` | `@live` | Health + info endpoint reachability |
| `ingest.feature` | `@embedded` | Project ingestion workflow |
| `ingestion-status.feature` | `@embedded` | Ingestion status polling |
| `analyze.feature` | `@embedded` | Clarification loop, plan generation, validation |
| `conventions.feature` | `@embedded` | Convention discovery and filtering |
| `ast-validation.feature` | `@embedded` | AST parsing, convention checking at code level |
| `doc-generation.feature` | `@embedded` | Document generation, type validation |
| `ui.feature` | `@ui` | Selenium UI acceptance tests |
| `token-budget.feature` | `@wip` | Token budget enforcement (step defs pending) |
| `semantic-cache.feature` | `@wip` | Semantic cache hit/miss (step defs pending) |
| `data-isolation.feature` | `@wip` | Multi-tenant data isolation (step defs pending) |
| `pr-creation.feature` | `@wip` | PR creation workflow (step defs pending) |
| `ci-remediation.feature` | `@wip` | CI webhook + remediation (step defs pending) |
| `convention-learning.feature` | `@wip` | Convention weight adjustment (step defs pending) |
| `guardrail.feature` | `@wip` | Prompt injection blocked, PII masked, schema violation rejected |
| `avenger-review.feature` | `@wip` | STARK verdict, LLM persona, full-review aggregation |
| `avenger-memory.feature` | `@wip` | HAWKEYE learns from repeated violations, per-Avenger scoping, cache hit |

### Frontend

| Test File | What It Tests | # Tests |
|-----------|--------------|---------|
| `AnalyzePage.test.tsx` | Full clarification loop, structured plan rendering, unknown refs, error states, reset | 11 |
| `IngestPage.test.tsx` | Form validation, submission, polling (success/failure), Try Again/Ingest Another | 10 |
| `ProjectsPage.test.tsx` | Project table, empty state, loading, error, navigation, "Never" for unindexed | 9 |
| `ConventionsPage.test.tsx` | Convention table with data, empty state, error, filters | 6 |
| `brainClient.test.ts` | All API client methods (GET/POST, params, response mapping) | 6 |
| `assurantTheme.test.ts` | Theme colors, font, palette mode | 3 |
| `Header.test.tsx` | Header renders title, hamburger toggle | 2 |
| `Sidebar.test.tsx` | Sidebar renders navigation, nav click calls onClose | 2 |
| `App.test.tsx` | App renders with router | 1 |
| `RequirementIntakePage.test.tsx` | Upload, ad-hoc, Jira intake flows | 8 |
| `TicketProposalPage.test.tsx` | Proposal generation, review, Jira creation | 6 |
| `PrStatusPage.test.tsx` | PR listing, status display, remediation history | 5 |
| `LearningDashboardPage.test.tsx` | Convention weights, learning events | 4 |
| `TokenUsagePage.test.tsx` | Summary cards, service breakdown, recent calls | 5 |
| `DocGeneratorPage.test.tsx` | Generate, error states, library tab, delete | 6 |
| `FullDocsPage.test.tsx` | Project picker, single-button generate, polling, COMPLETED/PARTIAL/FAILED status, From-cache badge | 5 |

### Verifying PDF bytes in tests

PDF rendering goes through Flying Saucer + a hardened XHTML parser. Tests should assert on the magic header rather than parsing the document:

```java
byte[] bytes = renderer.render("<h1>Project</h1>", "Project");
assertThat(bytes[0]).isEqualTo((byte) '%');
assertThat(bytes[1]).isEqualTo((byte) 'P');
assertThat(bytes[2]).isEqualTo((byte) 'D');
assertThat(bytes[3]).isEqualTo((byte) 'F');
```

For XSS / XXE security, assert the renderer **rejects** hostile input rather than asserting on a sanitized output: `assertThatThrownBy(() -> renderer.render(maliciousXml, "title")).isInstanceOf(IOException.class)`.

---

*For architecture context, see [Architecture](ARCHITECTURE.md). For contributing guidelines, see [Contributing](CONTRIBUTING.md).*
