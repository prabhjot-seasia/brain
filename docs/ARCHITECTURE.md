# Architecture

> **Who this document is for:** Developers who need to understand how Project Brain is built — its components, data flow, persistence layer, and design decisions. You should be familiar with Spring Boot basics (controllers, services, dependency injection), but you don't need prior experience with vector databases, graph databases, or AI/ML concepts.

---

## System Overview

Project Brain is a three-tier application:

```
┌─────────────────────────────────────────────────────────────┐
│                     CLIENT LAYER                             │
│  React Admin UI (Vite + MUI)  │  MCP Server (Claude Code)   │
│       localhost:3000           │     stdio (Node.js)         │
└──────────────┬─────────────────┴────────────┬───────────────┘
               │ HTTP /api/v1/*               │ HTTP /api/v1/*
┌──────────────▼──────────────────────────────▼───────────────┐
│                     API LAYER (Spring Boot)                   │
│  IngestController  │  AnalyzeController  │ ConventionsCtrl   │
│  IntakeController  │  DocGeneratorCtrl   │ JiraTicketCtrl    │
│  JiraOAuthCtrl     │  PrController       │ LearningController│
│  GitHubWebhookCtrl │                     │                   │
│  ──────────────────┼─────────────────────┼──────────────────│
│                   SERVICE LAYER                               │
│  IngestionService  │  ClarifierService  │  PlannerService    │
│  GitCloneService   │  ProjectDetector   │  DocGeneratorSvc   │
│  AnalysisFacade    │  CiRemediationSvc  │  TicketProposalSvc │
│  TicketCreationSvc │  DocumentExtractor │                    │
│                                                               │
│  ──────────────────┼─────────────────────┼──────────────────│
│                 PERSISTENCE LAYER                             │
│  PostgreSQL 16     │  Neo4j 5           │  LLM Provider     │
│  + pgvector        │  (knowledge graph) │  (Ollama/Bedrock)  │
└─────────────────────────────────────────────────────────────┘
```

---

## The Two Core Flows

Brain has two main operations: **Ingestion** (loading a project) and **Analysis** (answering questions about it).

### Flow 1: Ingestion

When you ingest a project, here's what happens step by step:

```
POST /api/v1/projects/ingest (JSON body with repoUrl + branch)
        │
        ▼
  IngestController
        │ validates request, returns 202 Accepted immediately
        │ (the rest happens asynchronously in a background thread)
        ▼
  GitCloneService.clone()
        │ shallow clone (depth=1) via JGit with PAT auth
        │ returns: temp directory path + commit SHA
        ▼
  ProjectDetector.detect()
        │ scans for build.gradle, pom.xml, package.json, etc.
        │ returns: language, framework, buildTool (auto-detected)
        ▼
  IngestionService.ingestProject() [@Async]
        │
        ├─── Is this the first ingest? ──► runFullIngestion()
        │         │
        │         ├── Walk all files in the cloned directory
        │         ├── Parse .java files → class chunks + method chunks (JavaParser AST)
        │         ├── Parse pom.xml → library dependencies (Maven Model Reader)
        │         ├── Parse .md/.txt/.yaml → doc chunks (charset-safe decoder)
        │         ├── Build vector documents with metadata + content hash
        │         ├── vectorStore.add(documents) → embed + store in pgvector
        │         └── projectNodeRepository.save(graph) → store in Neo4j
        │
        └─── Has existing chunks? ──► runIncrementalIngestion()
                  │
                  ├── Load existing content hashes from DB
                  ├── Walk new files, compute content hashes
                  ├── Classify: unchanged (skip), modified (re-embed), new (embed), deleted (remove)
                  ├── Only embed changed + new chunks
                  └── Delete chunks for removed files
```

**Key design decisions:**

- **@Async on a bounded thread pool.** Ingestion can take minutes for large projects. The controller returns 202 immediately so the UI doesn't hang. The thread pool is bounded to prevent OOM if someone submits 50 projects at once.
- **Content-hash-based incremental ingestion.** Each chunk gets a SHA-256 hash of its source content. On re-ingest, only files whose hash changed are re-embedded. A PR touching 3 files out of 500 costs ~$0.0001 instead of $0.12.
- **Failure rollback.** If ingestion fails mid-way (embedding service down, parse error), `handleIngestionFailure()` deletes partial chunks, Neo4j nodes, and the project row. The user gets a clean slate to retry.

### Flow 2: Analysis (Clarification Loop)

When you ask the Brain to analyze a requirement:

```
POST /api/v1/analyze (JSON: projectId, requirement, sessionId?, answers?)
        │
        ▼
  AnalyzeController
        │
        ▼
  AnalysisFacade.analyze()
        │
        ├─── classifyIntent(requirement)
        │       │
        │       ├── "Explain this project" → EXPLAIN intent
        │       │       │
        │       │       ▼ PlannerService.explainProject()
        │       │         (RAG retrieval + markdown summary prompt)
        │       │         → returns AnalyzeResponse(kind=EXPLAIN)
        │       │
        │       └── "Add rate limiting" → IMPLEMENT intent
        │               │
        │               ▼
        ├─── resolveSession() — load existing session or create new
        │
        ├─── appendAnswers() — if user sent answers from previous round
        │
        ├─── ClarifierService.analyze()
        │       │ builds prompt with: known projects, requirement, previous Q&A
        │       │ LLM scores confidence on 4 dimensions: WHY, WHAT, WHERE, HOW
        │       │
        │       ▼ parseClarificationResponse()
        │         │ strip markdown fences from LLM output
        │         │ parse JSON to ClarificationResponse
        │         │
        │         ▼ enforceServerSideConfidence()
        │           │ IGNORE the LLM's "confident" flag
        │           │ RECOMPUTE from dimension scores:
        │           │   average >= 0.7 AND every dimension >= 0.4 → confident
        │           │   any unknownReferences → NOT confident (always)
        │           │
        │           └── This is what stops the infinite loop
        │
        ├─── NOT confident AND rounds < 3?
        │       │ → appendQuestions to session
        │       │ → return AnalyzeResponse(planReady=false, questions=[...])
        │       │   (user answers, calls this endpoint again with sessionId + answers)
        │
        └─── Confident OR rounds >= 3?
                │ → PlannerService.generatePlan()
                │     retrieves: RAG code chunks + doc chunks + Neo4j conventions + graph context
                │     calls LLM with structured JSON plan prompt
                │ → save plan to session as JSONB
                │ → return AnalyzeResponse(planReady=true, plan="...")
```

**Key design decisions:**

- **Server-side confidence enforcement.** The LLM sometimes says "not confident" even when all dimensions score 0.9. The server overrides this using the actual numbers, preventing infinite loops.
- **Max 3 clarification rounds.** Safety valve. After 3 rounds, the plan is force-generated with whatever context exists. The response is flagged `forcedAfterMaxRounds=true` so the UI can warn.
- **EXPLAIN fast-path.** Read-only questions ("explain this project", "describe the auth flow") skip the clarifier entirely because there's nothing to clarify — no WHERE/HOW dimensions apply.
- **JSONB dirty-detection workaround.** Hibernate's dirty-checking on JSONB-mapped collections is unreliable. We always build a new list and call `setRounds()` to force detection.

---

## Package Structure

```
src/main/java/com/assurant/brain/
├── BrainApplication.java           # Spring Boot entry point
├── auth/
│   ├── JiraOAuthController.java   # GET /api/v1/auth/jira/connect|callback|status
│   └── JiraTokenStore.java        # AES-encrypted token persistence
├── ci/
│   ├── CiFailureParser.java       # Parses GitHub Actions log output for errors
│   ├── CiRemediationService.java  # Pushes LLM-generated fixes on CI failure (max N attempts)
│   └── GitHubWebhookController.java  # POST /api/v1/webhooks/github (HMAC-verified)
├── codegen/                        # Code generation from session plans
├── config/
│   ├── AsyncConfig.java            # Bounded thread pool for @Async ingestion
│   ├── EmbeddingHealthIndicator.java  # /actuator/health indicator for embeddings
│   ├── EmbeddingWarmupRunner.java  # Pre-loads Ollama model on startup
│   ├── LlmConfig.java             # Routes ChatModel bean by provider (Ollama/Anthropic/Bedrock)
│   ├── Neo4jConfig.java           # Dual transaction managers (JPA primary + Neo4j)
│   └── properties/
│       └── BrainProperties.java   # All brain.* config as a Java record (includes Jira, Intake, Ci sub-records)
├── dao/                            # Spring Data JPA repositories
│   ├── ChunkRepository.java       # Vector chunks with native SQL for JSONB queries
│   ├── ClarificationSessionRepository.java
│   ├── GeneratedDocumentRepository.java
│   ├── IntakeRecordRepository.java
│   ├── ProjectRepository.java
│   ├── PullRequestRecordRepository.java
│   ├── TicketProposalRepository.java
│   └── UserSessionRepository.java
├── docs/
│   ├── DocGeneratorController.java  # POST|GET|DELETE /api/v1/docs
│   └── DocGeneratorService.java   # RAG-backed markdown document generation
├── domain/                         # JPA entities
│   ├── Chunk.java                 # Embedding + metadata (projectId, filePath, contentHash)
│   ├── CiRemediationAttempt.java  # Tracks each automated CI fix push
│   ├── ClarificationSession.java  # JSONB rounds + final_plan
│   ├── GeneratedDocument.java     # LLM-generated markdown documents
│   ├── IntakeRecord.java          # Extracted text from uploads/Jira/ad-hoc
│   ├── Project.java               # Project metadata (language, framework, repoUrl, commitSha)
│   ├── PullRequestRecord.java     # Codegen PR lifecycle tracking
│   ├── TicketProposal.java        # Proposed + created Jira ticket records
│   └── UserSession.java           # Jira OAuth tokens (AES-encrypted)
├── dto/
│   ├── request/
│   │   ├── AnalyzeRequest.java
│   │   ├── IngestRequest.java
│   │   └── ProposedTicket.java
│   └── response/
│       ├── AnalyzeResponse.java
│       ├── ClarificationResponse.java
│       └── ErrorResponse.java
├── enums/
│   ├── ChunkType.java
│   ├── DocType.java               # EXPLANATION, HOW_TO, ADR, API_SPEC, etc.
│   ├── IngestionStatus.java
│   ├── IntakeSourceType.java      # DOCUMENT_UPLOAD, IMAGE_UPLOAD, ADHOC_TEXT, JIRA_TICKET
│   ├── IntakeStatus.java
│   ├── PrStatus.java              # GENERATING, REVIEWING, CREATING, CREATED, FAILED
│   ├── SessionStatus.java
│   ├── SourceType.java
│   └── TicketProposalStatus.java
├── errorhandler/
│   └── GlobalExceptionHandler.java
├── exceptions/
│   ├── IngestionException.java
│   ├── ProjectNotFoundException.java
│   └── SessionNotFoundException.java
├── facade/
│   └── AnalysisFacade.java
├── github/                         # GitHub API client (used by codegen PR creation)
├── graph/
│   ├── node/
│   │   ├── ProjectNode.java
│   │   ├── ModuleNode.java
│   │   ├── ClassNode.java
│   │   ├── ConventionNode.java
│   │   └── LibraryNode.java
│   └── repository/
│       ├── ConventionNodeRepository.java
│       └── ProjectNodeRepository.java
├── intake/
│   ├── DocumentExtractorService.java  # PDF/DOCX/image/text extraction (PdfBox, POI, Tesseract)
│   └── IntakeController.java       # POST /api/v1/intake/upload|adhoc|jira
├── jira/
│   ├── JiraClient.java            # Atlassian REST API v3 client
│   ├── JiraIssueMapper.java       # Maps Jira issue fields to AnalyzeRequest
│   ├── JiraTicketController.java  # POST /api/v1/jira/tickets/propose|create; GET /api/v1/jira/tickets
│   ├── TicketCreationService.java # Creates tickets in Jira via JiraClient
│   └── TicketProposalService.java # LLM-generates ticket proposals from content
├── learning/                       # Convention weight adjustment on merged PR analysis
├── monitor/                        # Metrics and observability hooks
├── rest/v1/
│   ├── analyze/controller/
│   │   └── AnalyzeController.java
│   ├── conventions/controller/
│   │   └── ConventionsController.java
│   ├── ingest/controller/
│   │   └── IngestController.java
│   └── pr/
│       ├── LearningController.java  # GET /api/v1/learning/events
│       └── PrController.java        # POST|GET /api/v1/pr; GET /api/v1/pr/{id}/reviews|remediations; POST /api/v1/pr/{id}/analyze-merge
└── service/
    ├── ClarifierService.java
    ├── GitCloneService.java
    ├── IngestionService.java
    ├── PlannerService.java
    └── ProjectDetector.java
```

---

## Persistence Layer

### PostgreSQL 16 + pgvector

Three tables, all managed by Liquibase:

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `projects` | Project metadata | `id`, `name`, `language`, `framework`, `build_tool`, `repo_url`, `commit_sha`, `ingestion_status` |
| `chunks` | Embedded code/doc chunks | `id`, `content`, `embedding` (vector(1024)), `metadata` (JSONB with projectId, filePath, sourceType, chunkType, contentHash) |
| `clarification_sessions` | Analysis conversation state | `id` (UUID), `project_id`, `requirement`, `rounds` (JSONB array), `final_plan` (JSONB), `status` |

The `chunks.embedding` column uses a **1024-dimensional HNSW index**. This dimension was chosen because both Ollama bge-m3 (local) and AWS Bedrock Titan v2 (production) produce 1024-dim vectors. Moving from local to AWS requires zero schema changes.

The `pg_trgm` extension is enabled by changeset `BRAIN-017` and `chunks.content` carries a GIN trigram index (`chunks_content_trgm_idx`, built `CONCURRENTLY`). `HybridRetrieverService` uses it for BM25-style lexical retrieval that fuses with the dense pgvector path via reciprocal rank fusion.

### Neo4j 5 (Knowledge Graph)

```
(Project) ─── HAS_MODULE ───► (Module) ─── CONTAINS ───► (Class)
    │    groupId: "com.acme.platform"                        │
    │    artifactId: "platform-core"                          │
    │    npmName: "@acme/ui-kit"                              │
    │                                                         │
    ├── FOLLOWS_CONVENTION ──► (Convention)                   │
    │                              rule: "Use constructor injection"
    │                              source: "CONTRIBUTING.md"
    │                              trustWeight: 1.5
    │
    ├── USES_LIBRARY ──► (Library)
    │                       name: "org.springframework.boot:spring-boot-starter-web"
    │                       version: "3.4.4"
    │
    └── DEPENDS_ON ──► (Project)    ← populated by CrossRepoEdgeBuilder
                          matched via groupId:artifactId or npmName
```

The graph is queried during plan generation to find:
- Which classes/modules are affected by a requirement (keyword match on class names)
- What conventions apply to a project (ordered by trust weight)
- What libraries a project uses (for dependency-aware planning)
- Cross-repo dependency closure — `findDependencies`, `findDependents`, `findTransitiveDependencies`

**`DEPENDS_ON` population.** Each `ProjectNode` carries self-identity (`groupId`, `artifactId`, `npmName`) harvested from the root `pom.xml` and/or root `package.json` during ingestion. After every successful ingest, `CrossRepoEdgeBuilder` rebuilds `DEPENDS_ON` edges for that project: it scans the project's `USES_LIBRARY` targets and matches them against other indexed projects' identities (Maven `groupId:artifactId` or npm package name). The rebuild is idempotent and bidirectional — it also writes incoming edges when the newly-ingested project's identity matches libraries referenced by already-indexed projects.

**Cluster A enrichment (Wave R).** Two additional Cluster A node types ship from Wave R:

- `SymbolReferenceNode` (R5) — SCIP-style symbol cross-reference. Attributes: `symbolFqn` (`com.example.OrderService` or `com.example.OrderService#place`), `kind` (`CLASS`/`METHOD`/`FIELD`), `language`, `scipScheme`, `declaredInClassFqn`. Built by `SymbolReferenceBuilder.rebuildForClass`. Queried for precise cross-repo blast-radius (rename/delete) by `findCallSites(symbolFqn)`.
- `CommunitySummaryNode` (R3) — GraphRAG hierarchical rollup. Attributes: `level` (1=module, 2=package), `communityKey`, `memberClassFqns`, `summary` (LLM-generated), `memberCountHash` (cache key). Built by `CommunitySummarizer.summarize` after every ingest in `IngestionService.rebuildCommunitySummariesQuietly`. Queried by `PlannerService.retrieveCommunitySummaries` for EXPLAIN intent.

## Retrieval Pipeline (Wave R)

Beyond pure vector search, `PlannerService.retrieveRagContext` runs:

```
                ┌──────────────────────────────┐
query ─────────►│ OraclePromptOptimizer        │   reads SLONode for project
                │   .computeBudget(projectId)  │   tier ∈ {HIGH, MEDIUM, LOW, DEFAULT}
                └────────────┬─────────────────┘   recallK ∈ {200, 100, 50}
                             │ rerankK ∈ {20, 10, 5}
                             ▼
                ┌──────────────────────────────────────────┐
                │ HybridRetrieverService.hybridSearch      │
                │   ├─ dense:   pgvector cosine (top recallK)
                │   ├─ lexical: pg_trgm SIMILARITY (top recallK)
                │   └─ fuse:    reciprocal rank fusion (RRF, k=60)
                └────────────┬─────────────────────────────┘
                             │ List<ScoredDocument>
                             ▼
                ┌──────────────────────────────────────────┐
                │ CrossEncoderReRanker.rerank              │
                │   text-similarity + RRF score blend      │
                └────────────┬─────────────────────────────┘
                             │ top rerankK
                             ▼
       ┌──────────────────────────────────────────────────┐
       │ ContextWindowManager.buildContext                │
       │   (CODE chunks, DOC chunks, conventions, graph)  │
       └────────────┬─────────────────────────────────────┘
                    │
                    ▼  initial plan generated
                ┌──────────────────────────────────────────┐
                │ IterativeContextEnricher.enrich          │  RepoCoder pattern
                │   re-query using draft plan as input     │
                └────────────┬─────────────────────────────┘
                             ▼
                  ContextualGroundingRail (post-LLM,
                  bedrock provider only — flags low
                  grounding score)
```

Empty hybrid result → `fallbackVectorRagContext` reverts to pure vector-only via `VectorStore.similaritySearch`. Each LLM call is wrapped in `RailChain.applyPreLlm` / `applyPostLlm` and tracked by `TokenUsageTracker`.

### Why Two Databases?

**PostgreSQL + pgvector** handles: structured metadata, vector similarity search, JSONB documents. It's great at "find the 10 code chunks most similar to this requirement."

**Neo4j** handles: relationship traversals. It's great at "find all classes in module X that import library Y and follow convention Z." Doing this in Postgres would require recursive CTEs that don't scale.

---

## Comprehensive Doc Bundle Pipeline (UX-Q2.2)

```
POST /api/v1/docs/full/{projectId}
        │
        ▼
FullDocRateLimiter (5/5min, ≥15s apart)  ──429──► client
        │
        ▼
FullDocBundleService.startGeneration(projectId)
        │
        ├── ReentrantLock per projectId — second concurrent caller gets the in-flight documentId
        ├── FullDocBundleAggregator.aggregate(projectId)
        │     ├── ProjectNodeRepository
        │     ├── ConventionNodeRepository (top by trustWeight)
        │     ├── IncidentNodeRepository
        │     ├── SLONodeRepository
        │     ├── TestRunNodeRepository (flaky)
        │     ├── CommunitySummaryNodeRepository
        │     └── PullRequestRecordRepository (project-scoped, capped at 20)
        │
        ├── computeHash(aggregate) → SHA-256 (project + per-row identity + counts)
        │
        ├── GeneratedDocumentRepository.findCachedFullDoc(projectId, FULL_PROJECT_PDF, hash, since=now-60min)
        │     └── HIT  ──200 fromCache:true──► return existing documentId
        │
        ├── INSERT generated_documents row (status=GENERATING, content_hash=hash, section_results={…PENDING})
        │
        └── async on brainLlmExecutor:
              ├── 5 × CompletableFuture.supplyAsync —— DocGeneratorService.generate(projectId, "", DocType)
              │      └── each call: RailChain.applyPreLlm → SemanticCacheService → ChatModel → TokenUsageTracker.track → RailChain.applyPostLlm
              ├── allOf(...).join()
              ├── stitchMarkdown(cover, sections, conventions, SLOs, incidents, flaky, PRs, generation metadata)
              ├── MermaidPreRenderer.preRender(md)
              │      └── ```mermaid``` fences swapped for placeholder tokens; mmdc rendered to SVG (30s timeout, fenced-source fallback)
              ├── MarkdownPdfRenderer.markdownToHtml(md)
              │      └── commonmark with TablesExtension + HeadingAnchorExtension + escapeHtml(true) + sanitizeUrls(true)
              ├── placeholder-swap injects pre-rendered SVG into HTML
              ├── MarkdownPdfRenderer.render(html, title)
              │      └── hardened DocumentBuilderFactory (FEATURE_SECURE_PROCESSING, no DOCTYPE, no external entities/DTD, no-op EntityResolver)
              │      └── Flying Saucer ITextRenderer.setDocument(...) → createPDF(...)
              ├── persist contentMd, contentPdf, section_contents (per-DocType OK content), section_results, generated_at
              └── status = COMPLETED (5/5 OK) | PARTIAL (<5) | FAILED (top-level exception)

GET /api/v1/docs/{id}/pdf
        │
        ▼
size > 50MB ──413──► client
        │
        ▼
200 application/pdf  +  Cache-Control: private, no-store  +  X-Content-Type-Options: nosniff
                     +  Content-Security-Policy: default-src 'none'; sandbox
                     +  Referrer-Policy: no-referrer

POST /api/v1/docs/{id}/retry-failed
        │
        ▼
FullDocBundleService.retryFailedSections(documentId)
        │
        ├── parse section_results — pick types where status != "OK"
        ├── set those types back to PENDING; pass only failed types into generateAsync
        ├── readExistingSectionContent(doc) loads OK sections from section_contents JSONB
        └── re-stitch + re-render → COMPLETED if all sections now OK
```

---

## Async Jobs + Push Notifications (UX-Q3)

Every heavy operation (>3 s wall-clock or multi-LLM-call) flows through a single async-job pipeline. Replaces per-flow polling and bespoke in-flight tables.

```
POST /api/v1/projects/ingest        (or /docs/full/{id}, /autodev/start, /avengers/full-review, …)
        │
        ▼
AsyncJobService.startOrAttach(jobType, targetKind, targetId, projectId)
        │
        ├── partial unique index uq_async_jobs_inflight (jobType, targetKind, targetId) WHERE status IN (QUEUED,RUNNING)
        │   makes the dedup check race-free at the DB level
        │
        ├── HIT  ──► return existing AsyncJob with attachedToExisting=true   (controller responds 202 + Location)
        │
        └── MISS ──► INSERT new row status=QUEUED, return attachedToExisting=false
                            │
                            ▼
                    @Async service method receives jobId
                            │
                            ├── markRunning(jobId, "Ingesting <projectId>")        publish progress event
                            ├── updateProgress(jobId, pct, msg)                    publish progress event (any number of times)
                            └── markSucceeded(jobId, result) | markPartial(...) | markFailed(jobId, err)
                                                                                   publish terminal event + close emitters

GET /api/v1/jobs/stream/{jobId}    (text/event-stream)
        │
        ▼
JobEventPublisher.subscribe(jobId, 600_000ms)
        │   ├── ConcurrentMap<UUID, List<SseEmitter>> — per-job fan-out
        │   ├── snapshot event sent immediately on subscribe (current row state)
        │   ├── progress events as updateProgress is called
        │   ├── terminal event (succeeded/failed/partial) → SseEmitter.complete()
        │   └── stale-emitter eviction on send-failure (mirrors FullDocRateLimiter LRU)
        │
        ▼
@PreAuthorize("@projectAccess.canReadJob(#jobId)")
        └── ProjectAccessService.canReadJob looks up the row's projectId, delegates to canRead(projectId)

GET /api/v1/jobs/{jobId}            polling fallback (returns the row as JSON) — always available for MCP / curl
GET /api/v1/jobs?projectId=…&limit=20    recent-jobs panel
```

**In-flight dedup contract.** Duplicate clicks always join the existing run, never spawn a duplicate. The controller's response shape (`JobStartResponse` with `attachedToExisting`) lets the UI choose the right snackbar copy ("Already ingesting — joined that run" vs. "Started").

**Push notifications, not polling.** The frontend subscribes via `EventSource` (`useJobStream` hook or `<JobProgress>` widget). `<JobToastWatcher>` mounted in `App.tsx` reads `localStorage.brain.jobs.watching` and fires snackbar + Web-Notification on terminal state — survives page navigation and OS-tab switching.

**Migration status — Phase C complete.** All eleven heavy ops now ship through `AsyncJobService`: `INGEST_PROJECT`, `FULL_DOC_BUNDLE` (incl. `RETRY_FAILED_SECTIONS`), `AVENGER_FULL_REVIEW`, `AVENGER_SINGLE_REVIEW`, `CI_REMEDIATION`, `TICKET_PROPOSAL`, `TICKET_CREATION`, `RULE_PACK_INSTALL`, `ANALYZE_REQUIREMENT`, `AUTODEV_PIPELINE` (the `execute` stage), and `MULTI_REPO_PR` (the `create-prs` stage). `COMMUNITY_SUMMARIZATION` surfaces as per-community progress events on the parent `INGEST_PROJECT` job. The pre-execute autodev stages (`start` / `clarify` / `plan`) and the round-1 `/analyze` call stay blocking — each is under 30 s and the synchronous response shape is what the existing UI consumes. Their async variants (`/analyze/start`, `/autodev/execute/start`, `/autodev/create-prs/start`) are additive endpoints; `AutonomousDevPage` already consumes the autodev async variants via `useJobStream` for the long-running execute and create-prs stages.

**Operational concerns.** A `JobEventPublisher.sendHeartbeats()` `@Scheduled` task fires `:hb` SSE comment frames at `BRAIN_JOBS_SSE_HEARTBEAT_MS` (default 15 s) so proxies and load balancers don't idle out long-running jobs; failed sends evict their emitters in the same loop. `AsyncJobRetentionTask` runs nightly (`BRAIN_JOBS_RETENTION_CRON`, default `0 30 3 * * *`) and deletes terminal `async_jobs` rows older than `BRAIN_JOBS_RETENTION_DAYS` (default 90), keeping the table bounded without an external archival step. New heavy operations that don't follow this pattern are `BLOCKED` by THANOS.

---

## LLM Provider Architecture

The `ChatModel` bean is routed at startup based on `brain.llm.provider`:

```
brain.llm.provider=ollama    → OllamaChatModel (local, free)
brain.llm.provider=anthropic → AnthropicChatModel (API key required)
brain.llm.provider=bedrock   → BedrockProxyChatModel (AWS IAM auth)
```

This is implemented in `LlmConfig.java` using `@ConditionalOnProperty`. The rest of the application only depends on the `ChatModel` interface — it never knows which provider is active.

Similarly, embeddings are routed by `brain.embed.provider`:

```
brain.embed.provider=ollama        → OllamaEmbeddingModel (bge-m3, 1024 dims)
brain.embed.provider=bedrock-titan → BedrockEmbeddingModel (Titan v2, 1024 dims)
brain.embed.provider=openai        → OpenAiEmbeddingModel (text-embedding-3-small, 1536 dims)
```

---

## Frontend Architecture

The Admin UI is a single-page React application:

```
ui/src/
├── App.tsx                 
├── main.tsx                
├── api/
│   └── brainClient.ts      
├── components/layout/
│   ├── Header.tsx          
│   └── Sidebar.tsx         
├── pages/
│   ├── projects/
│   │   ├── ProjectsPage.tsx   # Table of ingested projects, click to analyze
│   │   └── IngestPage.tsx     # GitHub URL form + polling for ingestion status
│   ├── analyze/
│   │   └── AnalyzePage.tsx    # Clarification loop UI + structured plan renderer
│   └── conventions/
│       └── ConventionsPage.tsx # Table of extracted conventions with category filter
└── theme/
    └── assurantTheme.ts    # MUI theme with Assurant brand colors (#006ebb)
```

The UI communicates with the backend via `/api/v1/*` — Vite proxies these requests to `http://localhost:8080` in development.

---

## MCP Server Architecture

The MCP (Model Context Protocol) server exposes Brain's capabilities as tools that Claude Code (and other MCP-compatible IDEs) can call directly.

```
mcp/src/
├── server.ts       # MCP server definition with 4 tools
└── (compiled to mcp/dist/server.js)
```

Tools exposed:
- `analyze_requirement` — start or continue an analysis session
- `get_conventions` — list conventions for a project
- `list_projects` — list all ingested projects
- `get_project` — get details of a specific project

The MCP server runs as a separate Node.js process, communicating over stdio with the IDE and making HTTP calls to the Brain API.

---

## Security Boundaries

| Boundary | Protection |
|----------|-----------|
| User input in API requests | Bean Validation (`@NotBlank`, `@URL`) on DTOs, validated at controller entry |
| Vector search filters | `FilterExpressionBuilder` — never concatenates user input into filter strings |
| GitHub PAT | Environment variable only (`BRAIN_GITHUB_TOKEN`), never logged, never in config files |
| Database credentials | Environment variables in local dev; Spring Cloud Config Server in AWS (encrypted at rest) |
| Docker container | Non-root user (`brain:brain`), JRE-only runtime image |

---

## Guardrails Layer

Every LLM call flows through a composable rail chain inspired by [NeMo Guardrails](https://arxiv.org/abs/2310.10501) and [JGuardrails](https://dev.to/ratila/jguardrails-production-ready-safety-rails-for-java-llm-applications-2aee).

```
User input → RailChain.applyPreLlm() → LLM → RailChain.applyPostLlm() → Parsed response
             (sanitize, block bad)              (validate schema, block leaks)
```

- **Pre-LLM rails:** `InputLengthRail` (priority 10) → `PromptInjectionRail` (20) → `PiiMaskRail` (30). Decision: PASS / BLOCK / MODIFY (JGuardrails model).
- **Post-LLM rails:** `OutputSchemaRail` (priority 10) → `OutputPiiRail` (20) → `OutputLengthRail` (30).
- `PromptInjectionClassifier` interface — default `RegexInjectionClassifier` with patterns at `resources/guardrails/prompt-injection-patterns.txt`. Pluggable for future ONNX classifier (StackOne Defender).
- `RailBlockedException` → HTTP 400 via `GlobalExceptionHandler`.
- Wired into: `ClarifierService`, `CodeReviewService`, `AvengerReviewer` (every LLM path).
- Metric: `brain_guardrail_outcome_total{rail,decision}`.

Full details: [Guardrails](GUARDRAILS.md).

---

## Avenger Services Layer

The 11 Avengers are addressable backend services + MCP tools. Each has a role (WORKER / SERVICE / SUPPORT — from [arXiv 2601.13671](https://arxiv.org/html/2601.13671v1)).

**Workers (6, parallelizable):** STARK, HAWKEYE, VISION, WIDOW, HULK, FURY
**Service (3, cross-cutting utilities):** FORGE, ORACLE, MANTIS
**Support (2, runs last):** JARVIS, THANOS

```
POST /api/v1/avengers/{name}/review  →  AvengerController
                                        ↓
                                  RailChain.pre  [guardrails]
                                        ↓
                                  AvengerReviewer
                                  ├── STARK: AstValidator + ConventionChecker (zero LLM)
                                  └── Others: persona prompt + LLM with schema validation
                                        ↓
                                  RailChain.post (JSON schema)
                                        ↓
                                  TokenUsageTracker.track(AVENGER_REVIEW)
                                        ↓
                                  Persist AvengerReview (avenger_reviews table)
                                        ↓
                                  Emit LearningEvent if non-APPROVED
```

`AvengerOrchestrator.runFullReview()` runs all 11 in parallel via `brainLlmExecutor`.

Full details: [Avengers API](AVENGERS_API.md).

---

## Avenger Memory Layer

Per-Avenger, per-project semantic memory built on top of the episodic `LearningEvent` stream. Pattern from [Analytics Vidhya 2026-04 on memory systems](https://www.analyticsvidhya.com/blog/2026/04/memory-systems-in-ai-agents/).

- **Episodic:** `learning_events` rows with `event_type = AVENGER_VIOLATION_OBSERVED`, `avenger_id` populated (nullable `avenger_id` column + composite index).
- **Semantic:** `AvengerMemorySnapshot` computed on-demand from episodic events, cached in Redis with 24h TTL. Key: `brain:avenger:{NAME}:memory:{projectId}` (sanitized to `[a-zA-Z0-9_-]`).
- `AvengerReviewer` emits one `LearningEvent` per issue on any non-APPROVED verdict + invalidates Redis cache.
- `AdaptivePromptBuilder.buildAdaptiveSection(projectId, avenger)` injects memory-driven reinforcement hints into Avenger persona prompts. Token-budgeted (`max-memory-hint-tokens`, default 300).
- Metrics: `brain_avenger_memory_hits_total{avenger}`, `brain_avenger_memory_misses_total{avenger}`.
- Graceful degradation: Redis failure returns empty snapshot — no cascade.

Full details: [Avenger Memory](AVENGER_MEMORY.md).

---

## Additional Persistence Tables

- **`avenger_reviews`**: `id`, `avenger`, `project_id`, `request_hash`, `verdict`, `issues jsonb`, `summary`, `tokens_in`, `tokens_out`, `latency_ms`, `created_at`. Composite index on `(avenger, project_id, created_at DESC)`.
- **`learning_events`** (extended): nullable `avenger_id varchar(16)` column + composite index on `(avenger_id, project_id, created_at DESC)`.

---

## Additional Package Structure

- `src/main/java/com/assurant/brain/guardrail/` — `Rail` interface, `RailChain`, `RailContext`, `RailResult`, `rails/` (6 built-ins), `classifier/` (injection detection), `exceptions/RailBlockedException`
- `src/main/java/com/assurant/brain/avenger/` — `AvengerReviewer`, `AvengerOrchestrator`, `AvengerPersonaLoader`, `dto/`
- `src/main/java/com/assurant/brain/rest/v1/avengers/` — `controller/AvengerController`, `dto/`
- `src/main/java/com/assurant/brain/learning/` — extended with `AvengerMemory`, `AvengerMemorySnapshot`, `ConventionKeyExtractor`

---

*For configuration details, see [Configuration](CONFIGURATION.md). For API specifics, see [API Reference](API_REFERENCE.md).*
