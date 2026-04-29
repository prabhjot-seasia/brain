# Contributing to Project Brain

> **Who this document is for:** New team members joining the project. This covers our code standards, how to make changes, and what to watch out for. Read this before your first PR.

---

## The Avengers — Quality Gates

Every change to this project is reviewed through the Avengers protocol — 11 expert personas covering code quality, security, testing, documentation, UI/UX, infrastructure, performance, token economy, code validation, learning, and cross-domain oversight. When using Claude Code, say **"Avengers"** to trigger a full review.

For the full Avengers protocol, see [Avengers API](AVENGERS_API.md).

---

## Code Standards (Non-Negotiable)

These rules apply to every file in this repository, every PR, every commit.

### 1. No Comments in Code

Code must be self-explanatory. If you need a comment to explain what code does, refactor the code until it doesn't need one. No Javadoc, no inline comments, no TODOs. Zero exceptions.

Bad:
```java
// Check if the user is active
if (user.getStatus() == Status.ACTIVE) {
```

Good:
```java
if (user.isActive()) {
```

### 2. Zero Code Smells

No dead code. No unused imports. No duplicated logic. No inconsistent naming. Every file should be grep-verified for dangling references before you submit a PR.

Run before committing:
```bash
# Check for unused imports (IntelliJ: Code → Optimize Imports)
# Check for dead code (IntelliJ: Analyze → Run Inspection by Name → "Unused declaration")
```

### 3. Security First

- No hardcoded credentials, even as defaults in config. All secrets via environment variables or Spring Cloud Config.
- Input validation at system boundaries (controller layer) using Bean Validation (`@NotBlank`, `@URL`).
- Vector search filters use `FilterExpressionBuilder` — never concatenate user input into filter strings.
- PATs and tokens are never logged, even at DEBUG level.

### 4. No Silent Error Swallowing

Every `.catch()` on an API call (frontend) and every `catch` block (backend) must either recover or show user-visible feedback. Silent `.catch(() => {})` is banned — the user must always know when something failed. In React, set an error state that renders an `Alert`. In Java, log the error and either rethrow or set a status the caller can check.

### 5. Single Responsibility

Every class does one thing. `GitCloneService` clones. `ProjectDetector` detects. `IngestionService` ingests. If a class name needs "And" in it, split it.

### 6. Follow Existing Patterns

Before creating something new, check how the existing code does it:

| If you're adding... | Look at... |
|---------------------|-----------|
| A new REST endpoint | `IngestController.java` |
| A new service | `ClarifierService.java` |
| A new config property | `BrainProperties.java` (add a sub-record) |
| A new exception | `ProjectNotFoundException.java` + `GlobalExceptionHandler.java` |
| A new Liquibase migration | `src/main/resources/db/changelog/versions/2026/` |
| A new BDD scenario | `src/test/resources/features/` + corresponding Steps class |
| A new React page | `ui/src/pages/analyze/AnalyzePage.tsx` |

---

## Project Conventions

These are the org-level conventions that Project Brain follows (same as ce-IMEI):

| Convention | Details |
|-----------|---------|
| **Dependency injection** | Constructor injection via `@RequiredArgsConstructor` (Lombok). Never field injection. |
| **Logging** | Log4j2 YAML config. Logback is explicitly excluded. Use `@Log4j2` annotation. |
| **Database migrations** | Liquibase YAML changelogs. Changeset IDs follow `BRAIN-NNN.NN` pattern. |
| **Testing** | TestContainers for PostgreSQL. `@MockitoBean` for Neo4j and external services. |
| **Vector queries** | `FilterExpressionBuilder` for safety. No string concatenation in filters. |
| **Chunk trust weights** | DOC chunks = 1.5x, CODE chunks = 1.0x |
| **Spring profiles** | `local` (dev), `test` (CI), `dev` (AWS dev). No profile named `production` — it's not needed yet. |
| **Error handling** | `GlobalExceptionHandler` with `@RestControllerAdvice`. Every custom exception gets a handler. |
| **Async operations** | `@Async` on a bounded `ThreadPoolTaskExecutor`. Never `@Transactional` + `@Async` together (ThreadLocal isolation). |
| **Code generation modes** | For ≤3-file changes use `CodeGeneratorService.generateAiderDiffs` (Aider SEARCH/REPLACE format — ~70% smaller LLM output, applier validates SEARCH block matches verbatim). For new files / wide refactors use the whole-file `generateCode`. The choice is automatic in `EditOrchestrator` based on plan-node scope. |
| **LLM call wrapping** | Every LLM invocation must go through `RailChain.applyPreLlm` on the input and `TokenUsageTracker.track` on the response. New services that call `ChatModel.call` directly without these wrappers will fail STARK + ORACLE review. |
| **UI: shared widgets — DRY enforced** | Every page MUST consume primitives from [`ui/src/components/widgets/`](../ui/src/components/widgets/) instead of hand-rolling them: `<ResponsiveTable>` for any table (the desktop-table + mobile-card-stack contract is encapsulated there), `<StatusChip>` for any status/severity/tier display (add new values to `statusRegistry.ts`, never inline switch statements), `<DataState>` for the loading/error/empty/data quad-state, `<EmptyState>` for empty-state cards, `<KeyValueCard>` for 2-column key/value lists, `<JobProgress>` for any heavy-operation progress UI driven by `/api/v1/jobs/stream/{jobId}`. `<JobToastWatcher>` is mounted once in `App.tsx`; pages register their in-flight jobs with `startWatchingJob(jobId, label)` so the snackbar + Web-Notification fires across page navigation. THANOS verifies via grep on every UI review (see [docs/avengers/THANOS.md §2.3](avengers/THANOS.md)). Re-implementing any of these patterns inline is a `CHANGES_REQUESTED` routed to VISION. |
| **Heavy operations — unified async-job pattern** | Every long-running operation (>3 s wall-clock or multi-LLM-call) flows through [`AsyncJobService`](../src/main/java/com/assurant/brain/jobs/AsyncJobService.java). The controller calls `startOrAttach(jobType, targetKind, targetId, projectId)`; if a row already exists in `(QUEUED|RUNNING)` for that triple, the same `jobId` is returned with `attachedToExisting=true` — the partial unique index `uq_async_jobs_inflight` makes this a race-free DB-level lock. The service writes progress via `markRunning` / `updateProgress` / `markSucceeded` / `markPartial` / `markFailed`. The frontend subscribes via `EventSource` to `/api/v1/jobs/stream/{jobId}` (use the `useJobStream` hook or `<JobProgress>` widget) and never polls. Duplicate clicks always join the existing run — never spawn a duplicate. New heavy operations that don't follow this pattern are `BLOCKED` by THANOS. |

---

## Development Workflow

### 1. Set Up Your Environment

Follow [Getting Started](GETTING_STARTED.md) to get the stack running locally.

### 2. Create a Feature Branch

```bash
git checkout -b feature/BRAIN-XXX-short-description
```

### 3. Make Your Changes

- Write code following the standards above
- Write tests for every new path (unit + integration)
- Run the full test suite locally before pushing:

```bash
# Backend
./gradlew check    # tests + coverage verification

# Frontend
cd ui && npx vitest run --coverage
```

### 4. Verify No Dead Code

```bash
# Check that nothing you removed is still referenced
grep -r "OldClassName" src/
grep -r "removedMethod" src/
```

### 5. Submit a PR

- Keep PRs focused — one feature or fix per PR
- Title under 70 characters
- Description explains WHY, not WHAT (the diff shows the WHAT)

---

## File Organization Rules

### Where to Put Things (Backend)

| Type | Location |
|------|----------|
| REST controllers | `src/main/java/.../rest/v1/{feature}/controller/` |
| Business services | `src/main/java/.../service/` |
| Facades (orchestrators) | `src/main/java/.../facade/` |
| JPA entities | `src/main/java/.../domain/` |
| JPA repositories | `src/main/java/.../dao/` |
| Neo4j nodes | `src/main/java/.../graph/node/` |
| Neo4j repositories | `src/main/java/.../graph/repository/` |
| Request DTOs | `src/main/java/.../dto/request/` |
| Response DTOs | `src/main/java/.../dto/response/` |
| Enums | `src/main/java/.../enums/` |
| Config classes | `src/main/java/.../config/` |
| Config properties | `src/main/java/.../config/properties/` |
| Exceptions | `src/main/java/.../exceptions/` |
| BDD features | `src/test/resources/features/` |
| BDD steps | `src/test/java/.../bdd/steps/` |
| Unit tests | Same package as the class under test in `src/test/` |
| Integration tests | `src/test/java/.../rest/v1/{feature}/` |

### Where to Put Things (Frontend)

| Type | Location |
|------|----------|
| Pages | `ui/src/pages/{feature}/` |
| Layout components | `ui/src/components/layout/` |
| API client | `ui/src/api/brainClient.ts` |
| Theme | `ui/src/theme/assurantTheme.ts` |
| Test setup | `ui/src/test/setup.ts` |

---

## Adding a New Liquibase Migration

1. Find the latest changeset ID in `src/main/resources/db/changelog/versions/`
2. Create a new YAML file (or add to the existing one for the current month)
3. Use the next ID in sequence: `BRAIN-005.01`, `BRAIN-005.02`, etc.
4. Add it to `db.changelog-master.yaml`

```yaml
- changeSet:
    id: BRAIN-005.01
    author: brain
    changes:
      - addColumn:
          tableName: projects
          columns:
            - column:
                name: new_field
                type: varchar(255)
```

---

## Adding a New REST Endpoint

1. Add request/response DTOs in `dto/` — typed records, never `Map<String, Object>`
2. Add the controller method in the appropriate controller
3. Add validation annotations (`@NotBlank`, `@Valid`) on the request DTO
4. Add an exception handler in `GlobalExceptionHandler` if needed
5. Write integration tests in `src/test/java/.../rest/v1/`
6. Add a BDD scenario in the appropriate `.feature` file

## Code Generation Modes

Brain has two paths for emitting code from a plan node — pick the right one for the change size:

- **Small changes (≤ `BRAIN_CODEGEN_SMALL_CHANGE_MAX_FILES`, default 3 files)** → `CodeGeneratorService.generateAiderDiffs` emits `SEARCH`/`REPLACE` blocks. ~70% fewer output tokens than full-file rewrites and the diff format makes apply errors loud (the `SEARCH` block must match source verbatim — `AiderDiffApplier` rejects fuzzy matches).
- **Larger changes** → full-file generation with self-review loop (max 3 iterations) and FORGE AST validation between nodes.

The mode is selected automatically by `EditOrchestrator` based on the plan-node file count. Don't override unless you have a reason — the threshold tracks the 70%-token-savings sweet spot.

## LLM Call Site Checklist

Every new LLM call must:

1. Inject `RailChain` and run `applyPreLlm` on user input before constructing the prompt — never feed raw user text into a system prompt.
2. Call `TokenUsageTracker.track` with the exact same prompt text passed to the LLM (the input/output sizes drive ORACLE budgets).
3. Run `RailChain.applyPostLlm` with the response and any schema path under `OutputSchemaRail.METADATA_SCHEMA_KEY`.
4. Use `SemanticCacheService` for prompts that repeat (cache key should be derived from the *sanitized* prompt, not the raw one).

The `DocGeneratorService.generate` method is the canonical reference implementation.

---

## Useful Links

| Resource | URL |
|----------|-----|
| Why this project exists | [docs/WHY_PROJECT_BRAIN.md](WHY_PROJECT_BRAIN.md) |
| Architecture deep-dive | [docs/ARCHITECTURE.md](ARCHITECTURE.md) |
| API Reference | [docs/API_REFERENCE.md](API_REFERENCE.md) |
| Configuration guide | [docs/CONFIGURATION.md](CONFIGURATION.md) |
| Testing guide | [docs/TESTING.md](TESTING.md) |
| DevOps runbook | [docs/DEVOPS_RUNBOOK.md](DEVOPS_RUNBOOK.md) |
| AWS deployment | [docs/SETUP_AWS.md](SETUP_AWS.md) |

---

*Questions? Ask in the team Slack channel or check the existing docs listed above.*
