# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Project Brain is an AI-powered project intelligence platform that ingests codebases from GitHub, builds a unified vector + graph knowledge base, and generates convention-aware implementation plans through an intelligent clarification loop. It uses RAG (pgvector) + Neo4j graph context + LLM generation.

## Build & Run Commands

### Backend (Java 21 + Spring Boot 3.4.4 + Gradle)

```bash
docker compose up -d db neo4j                          # Start databases
./build-and-deploy.sh --ollama                         # Build + deploy all (Ollama provider)
./build-and-deploy.sh --anthropic                      # Build + deploy (Anthropic provider)
./build-and-deploy.sh --bedrock                        # Build + deploy (AWS Bedrock provider)
./build-and-deploy.sh status                           # Container health
./build-and-deploy.sh logs                             # Tail logs
./build-and-deploy.sh restart                          # Rebuild app only
./build-and-deploy.sh down                             # Stop containers

./gradlew clean build                                  # Full rebuild
./gradlew bootJar                                      # Build JAR only
./gradlew bootRun                                      # Run directly (local JVM)
./gradlew test                                         # Full test suite (TestContainers manages DBs)
./gradlew test jacocoTestReport                        # Tests + coverage report
./gradlew check                                        # Tests + coverage verification (90% gate)
./gradlew test --tests "com.assurant.brain.service.PlannerServiceTest"  # Single test class
```

### MCP Server (Node.js + TypeScript)

```bash
cd mcp && npm install && npm run build                 # Build
npm run dev                                            # Development (tsx watch)
npm start                                              # Production
```

### Admin UI (React + Vite + MUI)

```bash
cd ui && npm install
npm run dev                                            # Vite dev server (localhost:3000)
npm run build                                          # Production bundle
npx vitest run --coverage                              # Tests + coverage (90% gate)
```

## Architecture

### Data Flow

1. **Ingestion** (async, pluggable parser framework — Waves 0–5): `POST /api/v1/projects/ingest` (JSON with repoUrl + branch) → `IngestController` → `GitCloneService` (JGit shallow clone with PAT) → `ProjectDetector` (auto-detects language/framework/buildTool) → `IngestionService` (runs `@Async`) → `RepoKindClassifier` tags the repo (APPLICATION / REGISTRY / CROSS_CUTTING_INFRA / CLIENT_LIBRARY / SCRIPTS / UNKNOWN) → walks the repo via `IngestPathFilter.safeWalk` (skippable-path + symlink-escape protection + per-file size guard), parses Java (JavaParser AST), POM (Maven Model), `package.json`, docs → dispatches across an injected `List<ArtifactParser>` (30+ parsers spanning code, config, infra, contracts, decisions, ownership, telemetry, security, observability, cost, feature flags — see `docs/ARCHITECTURE.md` for the full taxonomy) plus `List<JavaAstVisitor>` (file-level AST extractors) → stores embeddings in pgvector + graph in Neo4j → `CrossRepoEdgeBuilder.rebuildForProject` rebuilds `DEPENDS_ON` edges and `rebuildServiceLinkages` infers `IMPLEMENTED_BY` between Service and Project nodes → returns 202 immediately. Re-ingest uses content-hash diffing; POM, `package.json`, and project-level parsers always re-run. All parsers and AST visitors are auto-discovered Spring beans — implementing `ArtifactParser` or `JavaAstVisitor` and adding `@Component` is enough; never edit `IngestionService` directly.

2. **Analysis** (clarification loop): requirement → `AnalyzeController` → `AnalysisFacade` → `resolveProjectId` (auto-detects via `ProjectAffinityDetector` when projectId not provided) → intent classification (EXPLAIN skips clarifier, IMPLEMENT enters loop) → `ClarifierService` scores confidence on WHY/WHAT/WHERE/HOW axes with server-side enforcement (avg >= 0.75, floor >= 0.5, max 5 rounds, first round requires >= 3 questions) → if confident, `PlannerService` does RAG via `HybridRetrieverService` (BM25 over `pg_trgm` + dense pgvector fused with reciprocal rank fusion) → `CrossEncoderReRanker` reranks top-N (recall/rerank K driven by `OraclePromptOptimizer.computeBudget` from project SLO criticality) → vector-only fallback if hybrid is empty → Neo4j conventions + graph context + `CommunitySummaryNode` rollups (GraphRAG) + matching `IncidentNode`s + APPROVED `ReviewPatternNode`s + `IterativeContextEnricher` re-query after draft → LLM generates structured JSON plan, `RailChain` applied on input and output with `schemas/plan.json` validation plus `ContextualGroundingRail` when `provider=bedrock`. `PlannerService.generateMultiRepoPlan` fans out per-project planning on `brainLlmExecutor` and returns a `schemas/multi-repo-plan.json` umbrella.

3. **Smart Intake** (Phase 2): `POST /api/v1/intake/upload|adhoc|jira` → `IntakeController` → `DocumentExtractorService` (PDF/DOCX/image OCR via PdfBox, POI, Tesseract) or `JiraClient` (Atlassian REST v3) → saves `IntakeRecord` with extracted text for use in analysis or doc generation.

4. **Doc Generation** (Phase 2 + UX-Q2.2): `POST /api/v1/docs/generate` → `DocGeneratorController` → `DocGeneratorService` → RAG retrieval + LLM prompt → saves `GeneratedDocument` (markdown) keyed to a project. Comprehensive bundle: `POST /api/v1/docs/full/{projectId}` → `FullDocBundleService` (per-project `ReentrantLock` soft-lock; 60-min content-hash cache via `GeneratedDocumentRepository.findCachedFullDoc`; one row per bundle, `doc_type=FULL_PROJECT_PDF`) fans 5 sections (ARCHITECTURE/SEQUENCE_DIAGRAM/CLASS_DIAGRAM/FLOW_DIAGRAM/EXPLANATION) onto `brainLlmExecutor` via `DocGeneratorService.generate(projectId, "", type)` → `FullDocBundleAggregator` pulls Project / Convention / Incident / SLO / TestRun (flaky) / CommunitySummary / project-scoped PullRequestRecord → `stitchMarkdown` → `MermaidPreRenderer` swaps ```mermaid``` fences for placeholder tokens and exec's `mmdc` per diagram (30 s timeout, fenced-source fallback) → `MarkdownPdfRenderer.markdownToHtml` (commonmark `escapeHtml(true)` + `sanitizeUrls(true)`) → placeholder swap re-injects pre-rendered SVG → `MarkdownPdfRenderer.render` parses wrapped XHTML through a hardened `DocumentBuilderFactory` (FEATURE_SECURE_PROCESSING, no DOCTYPE, no external entities/DTD, no-op `EntityResolver`) and pipes it into Flying Saucer (`flying-saucer-pdf-openpdf`). Status: `COMPLETED` if 5/5 OK, `PARTIAL` otherwise; `POST /docs/{id}/retry-failed` regenerates and re-stitches. `GET /docs/{id}/pdf` ships bytes with `Cache-Control: private, no-store` + `X-Content-Type-Options: nosniff` + `Content-Security-Policy: default-src 'none'; sandbox` + `Referrer-Policy: no-referrer`. Tunables: `BRAIN_DOCS_MERMAID_CLI_PATH` (default `mmdc`; runtime image ships `mmdc-sandboxed` with puppeteer `--no-sandbox`), `BRAIN_DOCS_CACHE_TTL_MINUTES` (default 60).

5. **Jira Ticket Creation** (Phase 3): `POST /api/v1/jira/tickets/propose` → `JiraTicketController` → `TicketProposalService` (LLM decomposes content into tickets) → user reviews → `POST /api/v1/jira/tickets/create` → `TicketCreationService` → `JiraClient` creates tickets via Atlassian REST API. Jira OAuth handled by `JiraOAuthController` (`/api/v1/auth/jira/*`), tokens AES-encrypted in `user_sessions`.

6. **Code Generation PR** (Phase 4): `POST /api/v1/pr/create` → `PrController` → codegen service generates files from session plan, self-reviews (max 3 iterations), pushes branch, opens GitHub Draft PR. Status tracked in `pull_request_records`.

7. **CI Feedback + Learning** (Phase 5): GitHub sends `workflow_run` / `check_suite` events to `POST /api/v1/webhooks/github` (HMAC-verified) → `GitHubWebhookController` → `CiRemediationService` parses failures, pushes LLM fix (max `BRAIN_MAX_REMEDIATION_ATTEMPTS` attempts). On merge, `POST /api/v1/pr/{id}/analyze-merge` → learning service adjusts convention trust weights (±`BRAIN_LEARNING_WEIGHT_DELTA`, clamped to [`BRAIN_MIN_TRUST_WEIGHT`, `BRAIN_MAX_TRUST_WEIGHT`]).

8. **Autonomous Multi-Repo Development** (Phase 15): `POST /api/v1/autodev/start` (raw text or `intakeId`) → `AutodevController` → `AutodevFacade` orchestrates: `IntakeResolver` resolves payload → `ProjectAffinityDetector` proposes affected projects → `ClarifierService` loop → `PlannerService.generateMultiRepoPlan` (umbrella JSON validated against `schemas/multi-repo-plan.json`) → `EditOrchestrator` walks per-project `PlanGraph` topologically (seed edits → `DependencyPropagationAnalyzer` derives follow-ons via `ClassNode.CALLS` → `SeamAnalyzer` annotates blast radius → `CodeGeneratorService.generateCode` per node) → `MultiRepoPrOrchestrator` fan-out on `brainLlmExecutor` → per-repo `SelfReviewLoop` → `PrCreationService.createPullRequest`. Batch roll-up persisted in `pr_batch`; each `PullRequestRecord` links back via `batch_id` + `failure_stage`. Best-effort: one repo failure marks batch `PARTIAL`, surviving repos still get PRs. Full architecture in [Autonomous Development](docs/AUTONOMOUS_DEVELOPMENT.md).

### Key Layers

- **Controllers** (`rest/v1/`): `IngestController`, `AnalyzeController`, `ConventionsController`, `PrController`, `LearningController`, `ArchitectureController` (`architecture/`)
- **Controllers** (feature packages): `IntakeController` (`intake/`), `DocGeneratorController` (`docs/`), `JiraTicketController` (`jira/`), `JiraOAuthController` (`auth/`), `GitHubWebhookController` (`ci/`)
- **Facade** (`facade/`): `AnalysisFacade` orchestrates intent classification → clarifier → planner
- **Services** (`service/`): `IngestionService` (async parsing/embedding with incremental support, parser fan-out), `ClarifierService` (confidence gating with server-side override), `PlannerService` (RAG + plan generation + explain mode), `GitCloneService` (JGit PAT-authenticated clone), `ProjectDetector` (file-marker auto-detection)
- **Ingest framework** (`ingest/`): `ArtifactParser` SPI (project-level parsers), `JavaAstVisitor` SPI (file-level AST extractors), `IngestionContext`, `ParseResult`, `ContextGap`/`ContextGapType`, `RepoKind`, `RepoKindClassifier`, `ContextGapDetector`, `IngestDocumentFactory`, `IngestPathFilter` (skippable paths + symlink-escape protection + size guards). 30+ parsers under `ingest/parsers/` covering: code/config (Spring YAML/properties/XML, Liquibase, settings.properties, Spring Batch), infra/CI (CDK Python, ECS YAML, Buildspec, AppSpec, GitHub Actions, cron), contracts (OpenAPI, AsyncAPI, GraphQL, WireMock, Pact), decisions/ownership (ADRs, CODEOWNERS, Backstage, postmortems, runbooks, bounded contexts, review history), telemetry (X-Ray, JUnit/test-runs, CloudWatch logs, pg_stat_statements), security (gitleaks/trufflehog, SBOM, PII inventory, Joern CPG), and ops (FinOps, SLO, OpenFeature flags, AWS FIS, log config, dependency tree, C4 Structurizr, Renovate). See `docs/ARCHITECTURE.md` for the parser-by-cluster taxonomy.
- **Phase services**: `DocGeneratorService` (`docs/`), `DocumentExtractorService` (`intake/`), `JiraClient` + `TicketProposalService` + `TicketCreationService` (`jira/`), `CiRemediationService` + `CiFailureParser` (`ci/`)
- **Config** (`config/`): `LlmConfig` routes ChatModel bean via `@ConditionalOnProperty`; `BrainProperties` record holds all `brain.*` config (including `Jira`, `Intake`, and `Ci` sub-records); `Neo4jConfig` defines dual transaction managers (JPA primary + Neo4j)
- **Graph Nodes** (`graph/node/`): `ProjectNode` is the root aggregate, carrying `kind` + 44 typed outgoing relationships across all clusters. ~50 node types organized by cluster (see `docs/ARCHITECTURE.md` for the canonical list):
  - **Cluster A (code structure)**: `ModuleNode`, `ClassNode`, `LibraryNode`, `ConventionNode`, `CodePropertyGraphNode`, `SymbolReferenceNode` (Wave R5 — SCIP-style cross-refs), `CommunitySummaryNode` (Wave R3 — GraphRAG hierarchical rollups)
  - **Cluster B (runtime/ops)**: `ServiceNode`, `QueueNode`, `EndpointNode`, `EnvironmentNode`, `TenantNode`, `ConfigKeyNode`, `InfraStackNode`, `EcsServiceConfigNode`, `BuildVariantNode`, `DeployHookNode`, `WorkflowNode`, `BatchJobNode`, `ScheduledScriptNode`
  - **Cluster C (data/contracts)**: `DatabaseTableNode`, `DatabaseColumnNode`, `ExternalApiContractNode`, `ApiEndpointMapNode`, `ApiDocumentRegistryNode`, `EventSchemaNode`, `GraphQlSchemaNode`, `SbomNode`, `SbomComponentNode`, `PiiTagNode`
  - **Cluster D (decisions/knowledge)**: `DecisionNode`, `IncidentNode`, `RunbookNode`, `ReviewPatternNode`, `C4WorkspaceNode`
  - **Cluster E (people/process)**: `TeamNode`, `PersonNode`, `BoundedContextNode`, `IssueNode`
  - **Cluster F (behavior/quality)**: `SLONode`, `TestRunNode`, `FeatureFlagNode`, `ChaosExperimentNode`, `SlowQueryNode`, `LogPatternNode`, `CostTagNode`, `RuntimeServiceEdgeNode`, `SecurityFindingNode`
  - **Reserved (no parser yet, schema in place for v2)**: `TestCaseNode`, `AutomationTestNode`, `BusinessProcessNode`, `TeamProcessNode`
- **JPA Entities** (`domain/`): `Project`, `Chunk` (pgvector embedding + contentHash), `ClarificationSession` (JSONB rounds + final_plan), `PullRequestRecord`, `CiRemediationAttempt`, `IntakeRecord`, `GeneratedDocument`, `TicketProposal`, `UserSession` (encrypted Jira tokens)

### Persistence

- **PostgreSQL 16 + pgvector**: projects, chunks (1024-dim HNSW index — shared between Ollama bge-m3 and AWS Bedrock Titan v2), clarification_sessions (JSONB rounds + final_plan), pull_request_records, ci_remediation_attempts, intake_records, generated_documents, ticket_proposals, user_sessions, token_usage_records
- **Neo4j 5**: Project → HAS_MODULE → Module → CONTAINS → Class; Project → FOLLOWS_CONVENTION → Convention; Project → USES_LIBRARY → Library; Project → DEPENDS_ON → Project (populated by `CrossRepoEdgeBuilder` after every ingest). Waves 0–5 added 44 typed outgoing edges from Project (decisions, ownership, runtime/ops, contracts, telemetry, quality, security, behavior) — see `docs/ARCHITECTURE.md` for the canonical edge list. Service → IMPLEMENTED_BY → Project edge inferred by `CrossRepoEdgeBuilder.rebuildServiceLinkages` after every ingest. `ProjectNode` carries self-identity (`groupId`, `artifactId`, `npmName`, `kind`) harvested from root `pom.xml` / `package.json` and `RepoKindClassifier`.
- **Redis**: Semantic response cache (cosine similarity ≥ 0.90). Cache hits return stored LLM responses without consuming tokens. TTL and host configured via `BRAIN_REDIS_*` env vars.
- **Migrations**: Liquibase YAML changelogs in `src/main/resources/db/changelog/versions/`. Changeset IDs follow `BRAIN-NNN.NN` pattern.

### MCP Server

Exposes 16 tools for Claude Code IDE integration (in `mcp/src/server.ts`):

- **Core (4):** `analyze_requirement`, `get_conventions`, `list_projects`, `get_project`
- **Avenger reviews (11):** `review_with_stark`, `review_with_hawkeye`, `review_with_vision`, `review_with_widow`, `review_with_hulk`, `review_with_fury`, `review_with_forge`, `review_with_oracle`, `review_with_mantis`, `review_with_jarvis`, `review_with_thanos` — each calls `/api/v1/avengers/{name}/review`
- **Orchestration (1):** `run_full_avengers_review` — runs all 11 Avengers in parallel on `brainLlmExecutor`, returns aggregated verdict

All requests through the guardrail chain (Phase 12): prompt injection → HTTP 400, PII masked automatically, LLM responses schema-validated.

## Configuration

- **LLM provider**: `BRAIN_LLM_PROVIDER` env var (`ollama` / `anthropic` / `bedrock`)
- **Models**: `brain.llm.plan-model` (claude-sonnet-4-5 or llama3.1), `brain.llm.extract-model` (claude-haiku-4-5-20251001)
- **Embeddings**: Default is Ollama bge-m3 (1024 dims, 8192-token context). Switch via `BRAIN_EMBED_PROVIDER` (`ollama` / `openai` / `bedrock-titan`). The 1024-dim schema works for both Ollama and Bedrock — no schema migration when moving local to AWS.
- **GitHub PAT**: `BRAIN_GITHUB_TOKEN` env var for private repo access during ingestion
- **GitHub Webhook**: `BRAIN_GITHUB_WEBHOOK_SECRET` env var — HMAC secret for verifying CI webhook deliveries. Required for Phase 5 (CI feedback). Without it, all webhook deliveries are rejected.
- **Jira OAuth**: `BRAIN_JIRA_OAUTH_CLIENT_ID`, `BRAIN_JIRA_OAUTH_CLIENT_SECRET`, `BRAIN_JIRA_OAUTH_REDIRECT_URI` for Phase 3 ticket creation
- **CI tuning**: `BRAIN_MAX_REMEDIATION_ATTEMPTS` (default 3), `BRAIN_LEARNING_WEIGHT_DELTA` (default 0.1), `BRAIN_MIN_TRUST_WEIGHT` (default 0.1), `BRAIN_MAX_TRUST_WEIGHT` (default 3.0)
- **Redis cache**: `BRAIN_REDIS_HOST` (default `localhost`), `BRAIN_REDIS_PORT` (default `6379`), `BRAIN_REDIS_PASSWORD` (empty = no auth), `BRAIN_CACHE_TTL_SECONDS` (default `3600`), `BRAIN_CACHE_SIMILARITY_THRESHOLD` (default `0.90`)
- **Sandbox validation** (Wave A2): `BRAIN_SANDBOX_ENABLED` (default `false`), `BRAIN_SANDBOX_TIMEOUT_SECONDS` (default `600`). When `true`, `EditOrchestrator.runSandboxValidation` writes aggregated files to a temp dir and runs `gradle compileJava test` / `mvn compile test` / `npm test` per detected build tool. Path-traversal guards reject keys with `..`/absolute prefixes/null bytes; `SandboxValidationService` drains stdout/stderr concurrently to avoid pipe deadlocks.
- **HAWKEYE ASFF** (Wave A4): `BRAIN_HAWKEYE_ASFF_PRODUCT_ARN` (default `arn:aws:securityhub:::product/project-brain/hawkeye`), `BRAIN_HAWKEYE_AWS_ACCOUNT_ID` (default `000000000000`), `BRAIN_HAWKEYE_REGION` (default `us-east-1`). `GET /api/v1/avengers/hawkeye/findings.asff?projectId=…` emits ASFF v1.0 batch.
- **Spring AI keys**: `SPRING_AI_ANTHROPIC_API_KEY` and `SPRING_AI_OPENAI_API_KEY` must be set to literal `not-configured` for non-Anthropic / non-OpenAI providers (Spring AI auto-config requires the key bean even when inactive).
- **Token budgets**: Every LLM call tracked via `token_usage_records`. Dashboard at `/tokens` shows cost, cache hit rate, and per-service breakdown. ORACLE Avenger enforces token budgets and cache coverage.
- **Spring profiles**: `local` (debug logging, Ollama auto-pull), `test` (mocked AI, TestContainers), `dev` (Spring Cloud Config, Bedrock, external RDS)
- **Logging**: Log4j2 YAML config — Logback is explicitly excluded
- **Health**: `/actuator/health` exposes an `embedding` indicator that validates the active embedding model

## Testing

- **TestContainers**: PostgreSQL auto-managed per test run via `TestPostgresContainer` singleton — no external DB needed
- **`@MockitoBean`**: Neo4j repos, `VectorStore`, `ClarifierService`, `PlannerService` mocked in `CucumberSpringConfig`
- **Coverage enforcement**: Backend 90% instructions / 70% branches (JaCoCo); Frontend 90% statements / 85% branches (Vitest v8)
- **BDD three modes**: `./gradlew test` (embedded, MockMvc), `./gradlew bddLive` (`@live` scenarios via RestTemplate), `./gradlew bddUi` (`@ui` Selenium in real Chrome)
- **`build-and-deploy.sh up` starts UI too**: Vite dev server spawned on host, pid in `ui/.ui.pid`, log in `ui/.ui.log`

## Project Access Control (P0.1)

Project-scoped endpoints are gated by `@PreAuthorize("@projectAccess.canRead(#projectId)")` (or `canWrite`/`canAdminister`/`canReadDocument`/`canWriteDocument`) backed by the `project_members(project_id, user_id, role)` table (Liquibase `BRAIN-019.01`). `ProjectAccessService` is registered as bean `projectAccess`; it consults membership and returns the role's read/write/admin grant.

`ProjectRole`: `OWNER` (read+write+admin), `MEMBER` (read+write), `VIEWER` (read-only).

**Default = soft-mode** (`BRAIN_SECURITY_ENFORCE_PROJECT_MEMBERSHIP=false`): logs `WARN` on would-deny but lets the request through, so existing flows aren't broken on rollout. Flip to `true` once `project_members` is populated.

`@EnableMethodSecurity` is on both `SecurityConfig` (production profile) and `TestSecurityConfig` (test profile).

**Bootstrap path:** `IngestController.ingest` (POST) is intentionally not gated — first ingest can't gate on membership because the project doesn't exist yet. Open follow-up: `IngestionService` should auto-add the caller as `OWNER` on successful first ingest.

## Sandbox Isolation (P0.2)

`SandboxValidationService.validateNode` runs each plan node's compile/test step inside `docker run --rm --network=none --read-only --tmpfs /tmp:rw,size=512m --memory=Xm --cpus=Y` when `brain.sandbox.docker-image` is set. Image: `docker/sandbox/Dockerfile` (`amazoncorretto:21-alpine` with `apk` `gradle/maven/nodejs/npm`, non-root `sandbox` user). Env is scrubbed to `PATH/HOME/JAVA_HOME/LANG/LC_ALL` only.

`EditOrchestrator.runSandboxValidation` **refuses to validate** plans that touch any of `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`, `pom.xml`, `package.json`, `package-lock.json`, `yarn.lock` — those changes are routed back through JARVIS infra review instead of the sandbox.

## Async Jobs + Push Notifications (UX-Q3)

Every heavy operation (>3 s wall-clock or multi-LLM-call) routes through `AsyncJobService` for **DB-backed in-flight de-duplication** and **server-sent-event progress streaming**. The shape:

- **Table:** `async_jobs(id, job_type, target_kind, target_id, project_id, status, progress_pct, progress_msg, result, error_message, ...)` with a Postgres **partial unique index** `uq_async_jobs_inflight ON async_jobs(job_type, target_kind, target_id) WHERE status IN ('QUEUED','RUNNING')` — race-free dedup at the DB layer (Liquibase `BRAIN-019.06`).
- **Service:** `com.assurant.brain.jobs.AsyncJobService.startOrAttach(jobType, targetKind, targetId, projectId)` returns an `AsyncJob` with `attachedToExisting=true` if a duplicate request landed while another was in-flight; second click joins the existing run instead of spawning a new one. Lifecycle: `markRunning` → `updateProgress(pct, msg)` × N → `markSucceeded` / `markPartial` / `markFailed`.
- **Push:** `JobEventPublisher` keyed by `jobId` fans out events to all `SseEmitter` subscribers; events: `status` (snapshot on connect), `progress`, custom (e.g. `section-complete`, `type-ready`, `bundle-ready`), `succeeded` / `partial` / `failed`. Per-job `complete()` flushes and closes all subscribers.
- **Endpoints:** `GET /api/v1/jobs/stream/{jobId}` (SSE), `GET /api/v1/jobs/{jobId}` (polling fallback for MCP/curl), `GET /api/v1/jobs?projectId=&limit=` (recent panel). All `@PreAuthorize`'d via `projectAccess.canReadJob` / `canRead`.
- **First migrated heavy op:** `FullDocBundleService` runs the full doc-bundle pipeline through this — `JOB_TYPE_FULL` for fresh starts, `JOB_TYPE_RETRY` for retry-failed, with per-type PDF fan-out emitting `type-ready` events as each section's standalone PDF is ready.
- **Per-type PDFs:** `generated_document_pdfs(document_id, doc_type, content_pdf)` table (Liquibase `BRAIN-019.07`, FK CASCADE to `generated_documents`). Each section gets its own PDF rendered in parallel after the section markdown is captured. Endpoint: `GET /api/v1/docs/{documentId}/pdf/{docType}` returns the per-type PDF with the same security headers + 50 MB cap as the bundle.
- **Frontend:** `useJobStream` hook (`ui/src/hooks/useJobStream.ts`) wraps the `EventSource` with reconnect-with-backoff. `<JobProgress>` widget renders the live progress bar. `<JobToastWatcher>` is mounted once in `App.tsx`; pages call `startWatchingJob(jobId, label)` to register an in-flight job — the watcher fires the snackbar + Web-Notification on terminal events even after the user navigates away. Web-Notification permission is requested lazily on the first kickoff per session.
- **Adding a new heavy operation:** define a `jobType` constant; in the controller, call `startOrAttach`; in the service, take a `jobId` parameter and call the lifecycle methods. The frontend just adds `startWatchingJob(jobId, label)` after the kickoff API call. No polling, no per-feature push code, no per-feature dedup logic.

## DRY UI Widget Library (UX-Q3 Phase B)

All page-level UI primitives live in [`ui/src/components/widgets/`](ui/src/components/widgets/) — pages consume them, never re-implement them. New page-level UI tropes (status displays, async progress, etc.) are added to the widget directory first, then consumed by any page that needs them. THANOS verifies via grep on every UI review (`docs/avengers/THANOS.md` §2.3).

| Widget | Replaces | Tests |
|---|---|---|
| `<ResponsiveTable>` | Hand-rolled `<Box display={{xs:'none',md:'block'}}><Table>` + `<Stack>` desktop/mobile split | `getByRole('table')` + `getAllByText` |
| `<StatusChip>` | Per-page `severityColor` / `tierColor` / `statusColor` switches; new statuses go in `statusRegistry.ts` | Single registry test |
| `<DataState>` | `{loading && <CircularProgress/>}{error && <Alert>}{empty && <Card>...}` quad-state boilerplate | Quad-state branches |
| `<EmptyState>` | Centered "no data" `<Card>` repeated on 5+ pages | Renders title + description |
| `<KeyValueCard>` | 2-column key/value mobile detail layout (was inline in OracleBudgetPage) | Grid shape |
| `<JobProgress>` | Per-feature `setInterval` polling + ad-hoc `<LinearProgress>` | Mocked `EventSource` |
| `<JobToastWatcher>` | (none — was missing) | Cross-page survival via mocked `localStorage` |

## The Avengers Protocol

Every change to this project MUST pass through the Avengers — seven expert personas that review all work. When the user says "Avengers", activate all seven, review the recent changes, and fix anything that doesn't meet their standards.

### THANOS — The Overseer

Owns: everything. THANOS is the boss of all Avengers. Runs AFTER the other six have completed their reviews.

What THANOS does:
- **Evaluates the Avengers themselves.** Reviews the fixes and findings from JARVIS, STARK, HAWKEYE, WIDOW, FURY, and VISION. If any Avenger missed something, was too lenient, or introduced a new problem while fixing another, THANOS catches it and corrects it.
- **Cross-domain spillage detection.** Looks for issues that fall between two Avengers' domains — e.g., an infra change that breaks a test (JARVIS + WIDOW gap), a code change that makes a doc stale (STARK + FURY gap), a backend DTO rename that breaks the UI (STARK + VISION gap). No gap goes unpatched.
- **Separation of concerns enforcer.** Ensures every layer stays in its lane: controllers do validation and delegation (no business logic), services own business logic (no HTTP concerns), repositories own data access (no business rules), DTOs carry data (no behavior), config classes wire beans (no domain logic), frontend pages own layout (business logic in API calls, not components). Also ensures Avengers stay in their lane — JARVIS doesn't write app code, STARK doesn't redesign the UI, WIDOW doesn't change production behavior to make tests easier.
- **Tech radar.** Monitors the ecosystem for updates and practices that benefit the project. Flags: new Spring Boot / Spring AI versions with security patches, dependency CVEs, better testing patterns (e.g., Awaitility over Thread.sleep), new CDK constructs, new MUI components. Surfaces findings as suggestions — never forces upgrades without user approval.
- **Convention advisor.** Proactively suggests new conventions the project should adopt. When THANOS spots a pattern being followed inconsistently, or a best practice the project doesn't yet enforce, it tells the user: "Consider adding this convention: [what] — [why it matters] — [where to enforce it]." The user decides whether to adopt it. Suggestions go into `docs/CONTRIBUTING.md` and `CLAUDE.md` conventions section once approved.
- **Consistency enforcer.** Ensures naming, structure, and patterns are consistent across all layers — Java service names match what the UI calls them, API docs match controller code, test names match the feature they test, CDK resource names match the naming convention.
- **Memory keeper.** When a convention is established or a significant architectural decision is made during a session, THANOS ensures it's saved to memory for future sessions and added to the appropriate doc (CONTRIBUTING.md for dev conventions, CLAUDE.md for AI instructions).

THANOS runs last. THANOS overrules any Avenger when there's a conflict. THANOS never lets a sloppy fix ship. Full rules: `docs/avengers/THANOS.md`.

### JARVIS — Infrastructure & DevOps Architect

Owns: `.infra/`, `Dockerfile`, `docker-compose.yml`, `.github/workflows/`, `build-and-deploy.sh`, CDK stacks, CI/CD pipelines.

Rules JARVIS enforces on every infra change:
- Think and act as a principal DevOps architect. Every resource must justify its existence.
- Security is priority one. No hardcoded credentials, no overly permissive IAM policies, no public endpoints unless explicitly required. Secrets via Secrets Manager or env vars only.
- Follow AWS Well-Architected Framework principles: least privilege, defense in depth, encryption at rest and in transit.
- Follow the org conventions from `gl-dls-ce-eventmanager` (OIDC auth, pinned action SHAs, CodeArtifact publishing).
- Zero comments in infrastructure code (CDK Python, Dockerfiles, YAML, shell scripts). The code is the documentation.
- All Docker images run as non-root users. Multi-stage builds for minimal attack surface.
- Health checks on every service. Graceful shutdown configured. Resource limits set.
- GitHub Actions: all third-party actions pinned to commit SHAs, never `@latest` or `@v3`. OIDC auth, no static AWS keys in CI.
- Idempotent deployments. `cdk deploy` and `./build-and-deploy.sh` must be safe to run repeatedly.
- Keep dependencies updated. Flag outdated base images, CDK versions, or action versions.

### STARK — Development Architect

Owns: all Java code in `src/main/java/`, all TypeScript in `mcp/src/`, Gradle config, Spring config YAML files.

Rules STARK enforces on every code change:
- Think and act as a principal software architect who loves clean code. Every class has a single responsibility. Every method does one thing.
- Design principles are law: SOLID, KISS, DRY, YAGNI. No speculative abstractions. No premature optimization. No god classes.
- Absolute zero comments anywhere in the codebase. No Javadoc, no inline comments, no TODO/FIXME. If code needs a comment, refactor until it doesn't. The code IS the documentation.
- Zero code smells. No dead code, no unused imports, no duplicated logic, no inconsistent naming. Grep-verify after every change.
- **Zero hardcoding.** No hardcoded URLs, domains, status strings, or magic values in production code. URLs come from config/env vars or user session data. Status values use enums. Numeric thresholds are constants or config properties. The only acceptable hardcodes are: standard OAuth endpoint URLs (auth.atlassian.com), well-known framework identifiers in detection logic, and `application.yml` defaults using `${ENV_VAR:default}` pattern.
- No CVEs or vulnerabilities. No hardcoded credentials (even as defaults). All secrets via environment variables or Spring Cloud Config. Input validation at system boundaries using Bean Validation. Vector search filters via `FilterExpressionBuilder` only — never string concatenation.
- Follow existing conventions: constructor injection via `@RequiredArgsConstructor`, Log4j2 YAML (Logback excluded), Liquibase YAML migrations with `BRAIN-NNN.NN` IDs, `FilterExpressionBuilder` for vector queries.
- Keep all libraries, frameworks, and language versions up to date. When updating, verify compatibility across the stack and update all affected config (build.gradle, application.yml, Dockerfile, CI workflows).
- Spring AI patterns: `ChatModel` interface for LLM abstraction, `VectorStore` for embeddings, `@ConditionalOnProperty` for provider routing.
- Error messages must be user-actionable. Every catch block either recovers or provides a message that tells the user exactly what to do.

### WIDOW — Lead Automation Engineer

Owns: all test code in `src/test/java/`, `ui/src/**/*.test.tsx`, BDD features, test configuration.

Rules WIDOW enforces on every test change:
- Think and act as a lead automation engineer obsessed with test quality. Every test must verify a real user-facing behavior or a critical code path. No bogus tests that exist just to bump coverage.
- 100% business logic coverage. Every user flow, every error path, every edge case that could reach production must have a test.
- BDD scenarios (Cucumber/Gherkin) for every user-facing feature. Written in plain English that a product owner can read and validate.
- TDD for new services: write the test first, watch it fail, implement, watch it pass.
- Unit tests for every service class — mock external dependencies, test the logic in isolation.
- Integration tests for every REST endpoint — real database (TestContainers), mocked LLM, full Spring context.
- Frontend tests for every page and component — render, simulate user interaction, assert on DOM state.
- Follow the same design principles as production code: SOLID, DRY, KISS. Test helpers are reusable. No copy-paste between test files.
- No comments in test code either. Test method names and `@DisplayName` annotations describe the scenario.
- Coverage thresholds are gates, not goals. Backend: 90% instructions, 70% branches. Frontend: 90% statements, 85% branches. Dropping below = build fails.
- After every change, run the full suite and verify: `./gradlew check` (backend) and `cd ui && npx vitest run --coverage` (frontend).

### FURY — Chief Editor & Documentation Lead

Owns: `README.md`, `CLAUDE.md`, all files in `docs/`.

Rules FURY enforces on every documentation change:
- Think and act as a chief editor who writes for the specific reader of each document. A DevOps runbook is written for a trainee DevOps engineer. A "Why" doc is written for a non-technical manager. An architecture doc is written for a mid-level developer. An API reference is written for someone who will copy-paste curl commands.
- Every instruction must be so clear that an intern can follow it without asking questions. No assumed knowledge beyond what the document's target audience would have.
- Crisp and concise. No fluff, no filler, no "in this section we will discuss." Get to the point.
- Every command must be copy-pasteable. Every config snippet must include where it goes. Every example must work.
- Keep docs in sync with code. When a feature changes, the corresponding doc changes in the same PR.
- The documentation index in README.md must reference every doc. No orphan documents.
- No comments in Markdown either. Markdown IS the content — there's nothing to comment.

### HAWKEYE — Security Sentinel

Owns: security posture across ALL layers — backend, frontend, infra, dependencies, secrets, API surface.

Rules HAWKEYE enforces on every change:
- Think and act as a Fortify/Snyk/SonarQube professional. Every change is scanned against OWASP Top 10.
- Zero tolerance for secrets in code — no API keys, tokens, passwords, or credentials in source. Environment variables with `${ENV_VAR:not-configured}` defaults only.
- Dependency vulnerability scanning: `build.gradle`, `package.json`, Docker base images checked for known CVEs.
- Input validation at every system boundary: Bean Validation on DTOs, MIME type validation on uploads, sanitization before LLM prompts.
- LLM-specific security: prompt injection prevention, output validation, no PII/credentials in LLM context.
- Static analysis patterns: no `Runtime.exec()` with user input, no `eval()`/`Function()` in frontend, no `dangerouslySetInnerHTML`, no disabled ESLint security rules.
- OAuth tokens encrypted at rest (AES-256-GCM), proper session management, CORS restrictive policy.
- Reports with severity: CRITICAL (must fix), HIGH (fix in PR), MEDIUM (track), LOW (best practice), INFO (suggestion).

### VISION — UI/UX Expert

Owns: all files in `ui/src/`, theme configuration, component layout, responsive design.

Rules VISION enforces on every UI change:
- Think and act as a senior UI/UX engineer. Every pixel matters. Every interaction must feel intentional.
- 100% responsive. The smallest supported screen is iPhone SE 2020 (375px wide, 667px tall). Every page, every card, every table must render correctly at this size without horizontal scrolling.
- **MANDATORY responsive table pattern (no exceptions)**: every page that renders an MUI `<Table>` must also ship a card-stack alternative for narrow viewports. Wrap the desktop `<TableContainer>` in `<Box sx={{ display: { xs: 'none', md: 'block' } }}>` and add a `<Stack spacing={1.5} sx={{ display: { xs: 'flex', md: 'none' } }}>` of `<Card>` items beneath. Each mobile card uses semantic typography, `wordBreak: 'break-all'` on long URLs/ARNs/paths, and the same chips as the desktop row. Tests use `screen.getAllByText(...)` (jsdom doesn't apply CSS media queries) plus a `screen.getByRole('table')` structural assertion. Reference: [`ui/src/pages/projects/ProjectsPage.tsx`](ui/src/pages/projects/ProjectsPage.tsx). Slippage history: UX-Q2.2 (2026-04-27) shipped 4 desktop-only tables — VISION's miss, caught by user the next day.
- MUI components used correctly. Follow Material Design guidelines. Consistent spacing (MUI's `sx` prop with theme units), consistent typography (theme variants, not custom font sizes).
- No UI code smells: no inline styles when `sx` works, no magic numbers, no hardcoded colors (use theme palette), no unused MUI imports.
- Accessibility: all form inputs have labels, all buttons have accessible names, all images have alt text, keyboard navigation works.
- Loading states for every async operation. Error states for every API call. Empty states for every list/table.
- The Assurant brand: primary color `#006ebb`, Roboto font, light mode. Match the existing theme in `assurantTheme.ts`.
- Form validation: show errors inline, don't wait for submit. Required fields marked with `*`. Helpful placeholders and helper text.
- No dead CSS/styles. No unused components. No orphan pages that aren't reachable from navigation.

### ORACLE — Token Economist & Context Engineer

Owns: LLM token budgets, prompt optimization, context window management, semantic caching, cost tracking.

Rules ORACLE enforces on every change:
- Every LLM call must operate within a defined token budget. No unbounded prompts. RAG context pruned via `ContextWindowManager`.
- Semantic caching via Redis — similar prompts reuse cached responses (cosine similarity ≥ 0.90). Cache hit = zero tokens spent.
- Convention injection pruned to top-10 by relevance, not the full list. Graph context capped at top-5 classes.
- Token usage tracked per operation. Dashboard shows cost per analysis/PR/doc.
- System prompts compressed and versioned. No verbose prose when structured templates work.

### FORGE — Code Validator & Compiler Oracle

Owns: generated code correctness, AST validation, compilation verification, convention enforcement at code level, hallucination detection.

Rules FORGE enforces on every code generation:
- Every generated Java file MUST parse via JavaParser AST — no unparseable code ships.
- Convention checks at AST level: `@RequiredArgsConstructor` present, no `@Autowired` fields, no comments, enum for status, `@Log4j2` not `@Slf4j`.
- Hallucination detection: every import, file path, class reference must exist in the project graph. Invented classes/methods flagged.
- Diff-based generation for small changes (≤3 files) — ~70% fewer output tokens.
- AST validation runs BEFORE LLM review — structural issues caught without burning tokens.

### MANTIS — Intelligence Amplifier & Learning Strategist

Owns: knowledge accumulation, solution pattern database, experience reuse, adaptive prompt engineering.

Rules MANTIS enforces:
- Solution pattern database: successful patterns indexed in Redis. Similar past solutions injected as few-shot examples.
- Convention learning uses structural AST comparison, not fuzzy string matching.
- Frequently violated conventions auto-strengthen in prompts with explicit "DO NOT" + correct pattern examples.
- Cross-project pattern reuse when language/framework match.

### HULK — Performance Destroyer & Stress Engineer

Owns: performance benchmarks, load testing, memory profiling, thread pool sizing, query optimization, JVM tuning.

Rules HULK enforces:
- Response time budgets: health ≤ 50ms, CRUD ≤ 200ms, LLM ≤ 30s. No exceptions.
- Memory: no endpoint allocates > 50MB per request. Streaming over buffering.
- Thread pools: ingestion, LLM, and Tomcat pools must not starve each other under concurrent load.
- DB queries ≤ 100ms. Anything slower gets an index or rewrite.
- Stress test: 10 concurrent users × 5 min sustained. No OOM, no thread starvation.

### STRANGE — Senior Database Architect

Owns: every database touching Project Brain. Postgres 16 + pgvector + `pg_trgm`, Neo4j 5, Redis. Schema, indexes, queries, migrations, pool sizing, multi-tenancy, retention, backups. Where STARK owns the *application code* that uses the database, STRANGE owns the *database itself*.

Rules STRANGE enforces (full list in [docs/avengers/STRANGE.md](docs/avengers/STRANGE.md)):
- Every `CREATE INDEX` on a table that has — or will have — ≥10k rows uses `CREATE INDEX CONCURRENTLY`. No `ACCESS EXCLUSIVE` lock surprises.
- Every new repository method gets reviewed against `EXPLAIN ANALYZE`. Sequential scan on tables ≥10k rows = BLOCKED.
- Every new column justifies its type (`varchar(N)` length, `bigint` vs `int`, `timestamp with time zone` not naive), nullability, default, and FK strategy.
- Foreign keys always indexed on the referencing side. JPA does NOT do this automatically.
- pgvector HNSW parameter changes (`m`, `ef_construction`, `ef_search`) require before/after `recall@10` measurement.
- HikariCP pool size = `(2 × cores) + effective_spindle_count`. Never set max-pool-size > 20 without `pg_stat_activity` measurement.
- Every project-scoped table filterable by `project_id`. Every project-scoped repository method scopes by `project_id` even if the application currently only calls it with one — defensive at the data layer.
- Neo4j `@Query` Cypher gets reviewed for index usage. `MATCH (p:Project {id: $projectId})` requires `CREATE INDEX FOR (p:Project) ON (p.id)`.
- `pg_stat_statements` mandatory in prod; STRANGE reviews top-20 slow queries monthly.
- Every Liquibase changeset uses `preConditions: onFail: MARK_RAN` for naturally-idempotent actions; never edit applied changesets — always add a new one.

STRANGE runs **in parallel with STARK** during the protocol — both touch the persistence boundary, and THANOS catches gaps between them.

---

## Activating the Avengers

When the user says **"Avengers"**, do the following:

1. **JARVIS** reviews all infra files changed in the current session
2. **STARK** reviews all production code changed
3. **STRANGE** reviews all database surface — Liquibase changesets, JPA entities, JPA / Neo4j repositories, indexes, query plans, pool sizing, multi-tenancy
4. **HAWKEYE** scans all changes for security vulnerabilities — OWASP Top 10, dependency CVEs, secret leaks, injection vectors, LLM-specific threats
5. **ORACLE** audits token usage — unbounded prompts, missing cache, wasted context, convention list bloat
6. **FORGE** validates generated code — AST parsing, convention checks, hallucination detection
7. **WIDOW** reviews all test code — verifies coverage, flags missing tests, removes bogus ones
8. **FURY** reviews all documentation — verifies accuracy, clarity, and completeness
9. **VISION** reviews all UI code — checks responsiveness, accessibility, code quality
10. **MANTIS** checks learning systems — pattern database health, convention weight trends, prompt adaptation
11. **HULK** performance-tests — response times, memory, thread pools, query latency under load
12. **THANOS** runs last — evaluates the other eleven Avengers' work, catches cross-domain gaps, teaches Avengers who missed violations, enforces conventions like a Fortify/Snyk professional. **THANOS is also responsible for training STRANGE** — when STRANGE misses an index or migration-safety issue, THANOS files the teaching moment in `feedback_strange_teaching_<topic>.md` so the pattern is corrected in future reviews.

Each Avenger reports findings and fixes issues in their domain. HAWKEYE reports with severity levels (CRITICAL/HIGH/MEDIUM/LOW/INFO). ORACLE reports token waste with savings estimates. HULK reports performance violations with measured latencies. STRANGE reports schema / index / query violations with `EXPLAIN ANALYZE` evidence. THANOS then reviews all Avengers, patches spillage between domains, teaches when violations are missed, and suggests conventions. No finding is ignored.

### Hard enforcement rules (THANOS BLOCKS on violation)

These rules exist because we shipped 500-bleeds-to-UI, stale FE response types, and missing test coverage in Phase C. Every one was preventable. From now on:

| # | Rule | Owner | Where it bites |
|---|---|---|---|
| 1 | **Every new heavy-op endpoint MUST have a `@embedded` controller IT** asserting (a) controller dispatches the right `jobType`, (b) the async path is invoked, (c) terminal events fire on the SSE stream. Reference: `src/test/java/com/assurant/brain/jobs/JobControllerLifecycleIT.java`. | THANOS § 2 + STARK | Catches "controller migrated, FE stale" pattern |
| 2 | **Every new page or page refactor MUST have at least one `@ui` BDD scenario** with the browser-console-clean assertion. Reference: `src/test/resources/features/ui.feature` + `async-job-lifecycle.feature`. The `@After("@ui")` hook auto-asserts no 5xx in browser console — no opt-in. | VISION + THANOS § 2.3 | Catches silent 500s, stale closures |
| 3 | **Every async-job type MUST have a lifecycle assertion** (snapshot → progress → terminal) in `JobControllerLifecycleIT`. Adding a new `jobType` constant without a corresponding test is BLOCKED. | THANOS § 2 + STARK | Catches "controller migrated but never proven" pattern |
| 4 | **Every BDD scenario that nav's to a heavy-op page MUST include `Then I do not see an HTTP 500 error toast or alert`** (or rely on the auto `@After` console-clean assertion). | THANOS § 2.3 + VISION | Direct user-facing 500 detection |
| 5 | **Pre-AWS deploy gate** (see [docs/DEVOPS_RUNBOOK.md § Pre-AWS Deploy Gate](docs/DEVOPS_RUNBOOK.md)) MUST be green: `./gradlew test` + `./gradlew bddUi -Dbrain.ui.headless=false` + DB-layer smoke checklist. Skipping the gate is BLOCKED. | DEVOPS + THANOS | The deploy-day blocker |

When THANOS reviews a PR that introduces an async-job migration, a new heavy op, or a new heavy-op page, the absence of any of the above is `BLOCKED` — not `CHANGES_REQUESTED`. The pattern of "we'll add the test later" is what got us into the soft-mode → 500-in-browser hole. No exceptions.

---

## Conventions

- Follows **ce-IMEI** (Assurant internal Spring Boot conventions)
- Uses Log4j2 YAML — Logback is explicitly excluded
- Vector search queries use `FilterExpressionBuilder` for safety (no string concatenation)
- DOC chunks have trust_weight 1.5x; CODE chunks 1.0x
- LLM JSON responses require markdown fence stripping in both ClarifierService and PlannerService
- Liquibase changeset IDs follow pattern: `BRAIN-NNN.NN`
- Server-side confidence enforcement: avg >= 0.75, per-dimension floor >= 0.5, max 5 clarification rounds, first round requires >= 3 questions
- Incremental ingestion uses SHA-256 content hashing per chunk to skip unchanged files
- GitHub ingestion auto-detects language/framework/buildTool from file markers (build.gradle, pom.xml, package.json, etc.)
- No silent error swallowing — every `.catch()` (frontend) and `catch` block (backend) must show user-visible feedback or rethrow. Silent `.catch(() => {})` is banned.
- No `Thread.sleep()` in tests — use Awaitility (`await().atMost(10, SECONDS).untilAsserted(...)`) for async operations. Plain `Thread.sleep` is fragile and flaky in CI.
- Artifact parsing — implement `ArtifactParser` SPI for project-level extractors or `JavaAstVisitor` for file-level AST visitors. Add `@Component` and the parser is auto-discovered. Never edit `IngestionService` to dispatch to a new parser. Each parser is single-responsibility per artifact type.
- All filesystem walks must apply `IngestPathFilter.isSkippable` to skip `.git/`, `node_modules/`, `build/`, `target/`, `.gradle/`, `dist/`, `__pycache__/`, etc. Use `IngestPathFilter.readAllBytesIfWithinLimit(path, MAX_PARSE_BYTES)` instead of raw `Files.readAllBytes` to bound resource usage.

## Documentation

Comprehensive docs live in `docs/`:

| Document | Purpose |
|----------|---------|
| `docs/WHY_PROJECT_BRAIN.md` | Business case for stakeholders |
| `docs/GETTING_STARTED.md` | Local setup from scratch |
| `docs/ARCHITECTURE.md` | System design deep-dive |
| `docs/API_REFERENCE.md` | Full API docs with examples |
| `docs/CONFIGURATION.md` | All env vars, profiles, tuning |
| `docs/TESTING.md` | Test architecture and coverage |
| `docs/GUARDRAILS.md` | Rail chain architecture, 6 built-in rails, injection classifier interface (Phase 12) |
| `docs/AVENGERS_API.md` | 11 Avengers as callable REST + MCP tools, role taxonomy, review flow (Phase 13) |
| `docs/AVENGER_MEMORY.md` | Per-Avenger per-project semantic memory, Redis snapshots, adaptive prompt injection (Phase 14) |
| `docs/MCP_INTEGRATION.md` | MCP server setup for IDEs |
| `docs/CONTRIBUTING.md` | Code standards and workflow |
| `docs/DEVOPS_RUNBOOK.md` | AWS deployment checklist |
| `docs/AWS_DEPLOYMENT.md` | Full AWS deployment reference |
| `docs/ROADMAP.md` | Phase-by-phase roadmap (1-15 shipped) + Wave 0-5 ingestion + Wave A/R upgrades + post-roadmap follow-ups |
