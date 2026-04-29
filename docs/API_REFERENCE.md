# API Reference

> **Who this document is for:** Developers integrating with the Brain API — whether from the Admin UI, an MCP server, a CI pipeline, or curl. Every endpoint is documented with request/response examples you can copy-paste.

---

## Base URL

| Environment | URL |
|------------|-----|
| Local | `http://localhost:8080` |
| AWS Dev | `https://dev.hyla.hylatest.com/project-brain-backend` |

All endpoints are prefixed with `/api/v1` except health checks.

---

## Endpoints at a Glance

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/api/v1/auth/login` | Obtain a JWT token | None |
| `POST` | `/api/v1/auth/refresh` | Refresh your JWT token | Bearer JWT |
| `POST` | `/api/v1/projects/ingest` | Ingest a project from GitHub | Bearer JWT |
| `GET` | `/api/v1/projects` | List all projects | Bearer JWT |
| `GET` | `/api/v1/projects/{id}` | Get project details | Bearer JWT |
| `GET` | `/api/v1/projects/{id}/status` | Get ingestion status | Bearer JWT |
| `GET` | `/api/v1/projects/{id}/conventions` | List project conventions | Bearer JWT |
| `GET` | `/api/v1/projects/{id}/architecture` | Service/queue/endpoint topology view of the project | Bearer JWT |
| `POST` | `/api/v1/analyze` | Analyze a requirement (clarify or generate plan) | Bearer JWT |
| `POST` | `/api/v1/analyze/start` | Async variant — returns jobId + SSE stream URL | Bearer JWT |
| `POST` | `/api/v1/autodev/execute/start` | Async variant of /autodev/execute (long-running, 2–20 min) | Bearer JWT |
| `POST` | `/api/v1/autodev/create-prs/start` | Async variant of /autodev/create-prs (1–10 min) | Bearer JWT |
| `POST` | `/api/v1/intake/upload` | Upload a document or image for text extraction | Bearer JWT |
| `POST` | `/api/v1/intake/adhoc` | Submit ad-hoc text as an intake record | Bearer JWT |
| `POST` | `/api/v1/intake/jira` | Fetch a Jira ticket by key or URL and extract its text | Bearer JWT + Jira OAuth |
| `POST` | `/api/v1/docs/generate` | Generate a markdown document from a project + prompt | Bearer JWT |
| `GET` | `/api/v1/docs` | List generated documents for a project | Bearer JWT |
| `GET` | `/api/v1/docs/{id}` | Get a generated document by ID | Bearer JWT |
| `DELETE` | `/api/v1/docs/{id}` | Delete a generated document | Bearer JWT |
| `POST` | `/api/v1/jira/tickets/propose` | Propose Jira tickets from content using LLM | Bearer JWT |
| `POST` | `/api/v1/jira/tickets/create` | Create proposed tickets in Jira | Bearer JWT + Jira OAuth |
| `GET` | `/api/v1/jira/tickets` | List ticket proposals for a user | Bearer JWT |
| `GET` | `/api/v1/auth/jira/connect` | Start Jira OAuth flow — returns Atlassian auth URL | Bearer JWT |
| `POST` | `/api/v1/auth/jira/callback` | Complete Jira OAuth — exchanges code for tokens | None |
| `GET` | `/api/v1/auth/jira/status` | Check Jira connection status for a user | Bearer JWT |
| `POST` | `/api/v1/pr/create` | Generate code and create a GitHub Draft PR | Bearer JWT |
| `GET` | `/api/v1/pr` | List all PRs (optionally filtered by sessionId) | Bearer JWT |
| `GET` | `/api/v1/pr/{id}` | Get PR details | Bearer JWT |
| `GET` | `/api/v1/pr/{id}/reviews` | Get self-review history for a PR | Bearer JWT |
| `GET` | `/api/v1/pr/{id}/remediations` | Get CI remediation attempts for a PR | Bearer JWT |
| `POST` | `/api/v1/pr/{id}/analyze-merge` | Trigger learning from a merged PR | Bearer JWT |
| `POST` | `/api/v1/webhooks/github` | Receive GitHub workflow/check_suite events | HMAC signature |
| `GET` | `/api/v1/learning/events` | List convention weight adjustment events | Bearer JWT |
| `GET` | `/api/v1/jobs/{jobId}` | Get async-job snapshot (polling fallback) | Bearer JWT |
| `GET` | `/api/v1/jobs/stream/{jobId}` | Subscribe to async-job event stream (SSE) | Bearer JWT |
| `GET` | `/api/v1/jobs` | List recent async jobs (filter by `projectId`/`status`) | Bearer JWT |
| `GET` | `/actuator/health` | Health check | None |

---

## Authentication

All endpoints except `/api/v1/auth/login`, `/api/v1/auth/jira/callback`, `/api/v1/webhooks/**`, and `/actuator/**` require a `Bearer` JWT in the `Authorization` header.

### POST /api/v1/auth/login

Obtain a JWT. Pass `userId` in the request body — this is the caller's identity (e.g. a username or service account name).

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userId": "alice"}'
```

**Response (200 OK):**

```json
{"token": "<jwt>", "userId": "alice"}
```

### POST /api/v1/auth/refresh

Exchange a still-valid JWT for a new one with a fresh expiry. Requires a valid `Bearer` token.

```bash
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  -H 'Authorization: Bearer <jwt>'
```

**Response (200 OK):**

```json
{"token": "<new-jwt>", "userId": "alice"}
```

### Using the token

Add `Authorization: Bearer <jwt>` to every subsequent request:

```bash
curl http://localhost:8080/api/v1/projects \
  -H 'Authorization: Bearer <jwt>'
```

### Rate Limits

All authenticated users are subject to per-minute rate limits enforced server-side:

| Endpoint group | Limit |
|----------------|-------|
| LLM endpoints (`/analyze`, `/docs/generate`, `/jira/tickets/propose`, `/pr/create`) | 10 requests/min |
| All other `/api/v1/**` endpoints | 60 requests/min |

When a limit is exceeded the server responds with `429 Too Many Requests` and a `Retry-After: 60` header.

---

## POST /api/v1/projects/ingest

Start ingesting a project from a GitHub repository. Returns immediately (202 Accepted) while ingestion runs in the background.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/projects/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": "ce-imei",
    "projectName": "ce-IMEI Validation Service",
    "repoUrl": "https://github.com/assurant/gl-dls-ce-imei.git",
    "branch": "master",
    "description": "IMEI validation and screening service"
  }'
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | Yes | Unique identifier (used in all subsequent API calls) |
| `projectName` | string | Yes | Human-readable display name |
| `repoUrl` | string | Yes | GitHub HTTPS clone URL (must be a valid URL) |
| `branch` | string | No | Branch to clone (defaults to `master`) |
| `description` | string | No | Optional project description |
| `manifest` | object | No | Optional ingest manifest — supplies context that can't be inferred from repo files alone (environments, tenants, authoritative API registry, cloud-config repo metadata). See "Ingest Manifest" below. |

### Ingest Manifest

The manifest lets callers supply *out-of-repo* context that file-based parsers cannot discover. All fields are optional; only the parts you supply get applied. The `ManifestProcessor` runs after the file-based parsers and adds the manifest-derived nodes to the project graph.

```bash
curl -X POST http://localhost:8080/api/v1/projects/ingest \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": "ce-app",
    "projectName": "ce-app",
    "repoUrl": "https://github.com/.../gl-dls-ce-app.git",
    "branch": "master",
    "manifest": {
      "environments": [
        { "name": "dev",  "baseDomain": "dev.hyla.hylatest.com",  "awsAccount": "111111111111" },
        { "name": "uat",  "baseDomain": "uat.hyla.hylatest.com",  "awsAccount": "222222222222" },
        { "name": "prod", "baseDomain": "hyla.hylamobile.com",    "awsAccount": "333333333333" }
      ],
      "tenants": ["vzw", "att", "google", "telus"],
      "apiContractRegistry": {
        "projectId": "ce-api-document",
        "masterSpecPath": "src/main/resources/main-spec.yaml",
        "definitionsPath": "src/main/resources/definitions"
      },
      "cloudConfig": {
        "configServerBranch": "brain-01",
        "configRepoUrl": "https://github.com/.../gl-dls-ce-config-files",
        "configRepoPath": "env"
      }
    }
  }'
```

| Manifest field | Effect on the graph |
|---------------|---------------------|
| `environments[]` | Each entry becomes an `EnvironmentNode` linked to the project via `HAS_ENVIRONMENT`. |
| `tenants[]` | Each entry becomes a `TenantNode` linked via `SERVES_TENANT` (deduplicated against tenants the `SpringPropertiesParser` already discovered from filename suffixes). Source is tagged `MANIFEST` vs `PROPERTIES_SUFFIX` so you can tell them apart. |
| `apiContractRegistry` | Creates an `ApiDocumentRegistryNode` pointing at the org-wide OpenAPI registry project (typically `ce-api-document`) — used by `OpenApiAggregateParser` and downstream service-contract resolution. |
| `cloudConfig` | Captured for logging today; will be consumed by future cloud-config-repo cross-project linker. |

### Response (202 Accepted)

```
HTTP/1.1 202 Accepted
Location: /api/v1/jobs/<jobId>
```

```json
{
  "jobId": "8c9a…",
  "jobType": "INGEST_PROJECT",
  "status": "QUEUED",
  "attachedToExisting": false,
  "streamUrl": "/api/v1/jobs/stream/8c9a…",
  "pollUrl": "/api/v1/jobs/8c9a…"
}
```

If a duplicate request lands while an ingest for the same projectId is already QUEUED/RUNNING, the response carries `attachedToExisting: true` with the existing `jobId` — clients should join that run rather than retry.

### Error Responses

| Status | When | Example Body |
|--------|------|-------------|
| 400 | Missing required field | `{"message": "projectId: must not be blank"}` |
| 400 | Invalid URL format | `{"message": "repoUrl: must be a valid URL"}` |
| 403 | Caller lacks write access on existing project | `{"error": "no write access to project <id>"}` |

### Tracking progress

Use the unified async-jobs API — see [`/api/v1/jobs/*`](#async-jobs-asyncjobservice). Subscribe to `streamUrl` (SSE) for live progress, or fall back to polling `pollUrl`. Terminal statuses: `SUCCEEDED`, `PARTIAL`, `FAILED`.

The legacy endpoint `GET /api/v1/projects/{id}/status` remains available as an audit shortcut (returns `Project.ingestionStatus`).

---

## GET /api/v1/projects

List all ingested projects.

### Request

```bash
curl http://localhost:8080/api/v1/projects
```

### Response (200 OK)

```json
[
  {
    "id": "ce-imei",
    "name": "ce-IMEI Validation Service",
    "language": "java",
    "framework": "spring-boot",
    "buildTool": "gradle",
    "description": "IMEI validation and screening service",
    "lastIngested": "2026-04-12T10:30:00Z",
    "ingestionStatus": "COMPLETE",
    "repoUrl": "https://github.com/assurant/gl-dls-ce-imei.git",
    "branch": "master",
    "commitSha": "a1b2c3d4e5f6..."
  }
]
```

Returns an empty array `[]` if no projects have been ingested.

---

## GET /api/v1/projects/{id}

Get details of a specific project.

### Request

```bash
curl http://localhost:8080/api/v1/projects/ce-imei
```

### Response (200 OK)

Same shape as a single item from the list endpoint.

### Error Responses

| Status | When |
|--------|------|
| 404 | Project ID doesn't exist |

---

## GET /api/v1/projects/{id}/status

Get the ingestion status of a project. Useful for polling after starting an ingestion.

### Request

```bash
curl http://localhost:8080/api/v1/projects/ce-imei/status
```

### Response (200 OK)

```json
{
  "projectId": "ce-imei",
  "status": "COMPLETE",
  "error": null
}
```

If ingestion failed:

```json
{
  "projectId": "ce-imei",
  "status": "FAILED",
  "error": "Repository not found: https://github.com/org/repo.git. Check the URL and ensure the PAT has repo access."
}
```

---

## GET /api/v1/projects/{id}/conventions

List conventions extracted from a project's codebase.

### Request

```bash
# All conventions
curl http://localhost:8080/api/v1/projects/ce-imei/conventions

# Filter by category
curl "http://localhost:8080/api/v1/projects/ce-imei/conventions?category=error-handling"
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `category` | query string | No | Filter by category (e.g., `string-handling`, `db-migration`, `http-client`, `logging`, `error-handling`, `security`) |

### Response (200 OK)

```json
[
  {
    "id": 1,
    "rule": "Use constructor injection via @RequiredArgsConstructor, not field injection",
    "category": "coding-style",
    "sourceFile": "CONTRIBUTING.md",
    "trustWeight": 1.5,
    "projectId": "ce-imei"
  },
  {
    "id": 2,
    "rule": "Log after commit, not before — ensures log entries only appear for successful operations",
    "category": "logging",
    "sourceFile": "inferred from 23 occurrences",
    "trustWeight": 1.0,
    "projectId": "ce-imei"
  }
]
```

**Trust weight:** Conventions from documentation (`trustWeight >= 1.5`) are weighted higher than patterns inferred from code (`trustWeight = 1.0`).

---

## GET /api/v1/projects/{id}/architecture

Returns the service-topology view of a project — services it calls, queues it publishes/consumes from, endpoints it exposes — populated by the Wave 0/1 ingestion expansion (`SpringYamlParser`, `MessageListenerAnnotationParser`, `RestClientCallsiteParser`, `SpringXmlContextParser`, `WireMockContractParser`, `OpenApiContractParser`).

### Request

```bash
curl http://localhost:8080/api/v1/projects/ce-imei/architecture
```

### Response (200 OK)

```json
{
  "projectId": "ce-imei",
  "projectName": "ce-imei",
  "kind": "APPLICATION",
  "calledServices": [
    {
      "id": "ce-imei:service:catalog",
      "name": "catalog",
      "baseUrlTemplate": "https://${devops.common-services.host}/hylacatalog",
      "inferredProjectId": "catalog",
      "source": "APPS_YAML"
    }
  ],
  "publishesTo": [],
  "consumesFrom": [
    {
      "id": "ce-imei:queue:sqs:imei-validation-results",
      "queueType": "SQS",
      "name": "imei-validation-results",
      "arn": null,
      "source": "ANNOTATION"
    }
  ],
  "exposedEndpoints": [],
  "counts": {
    "calledServices": 1,
    "publishesTo": 0,
    "consumesFrom": 1,
    "endpoints": 0
  }
}
```

### Error Responses

- `404 Not Found` — project does not exist in the graph

---

## POST /api/v1/analyze

The main analysis endpoint. Supports three modes:

1. **New analysis** — send `projectId` + `requirement` (no `sessionId`)
2. **Continue clarification** — send `projectId` + `requirement` + `sessionId` + `answers`
3. **Explain request** — send a read-only requirement like "explain this project" (auto-detected, skips clarification)

> **Async variant:** `POST /api/v1/analyze/start` returns `JobStartResponse` (202) and runs the analysis on `brainLlmExecutor`; subscribe to `streamUrl` for progress + the terminal `succeeded` event whose `result` carries the `AnalyzeResponse`. Dedup target = `sessionId` (or `new:<uuid>` for a fresh session). Useful for round-2+ where plan generation can take 30 s+. The blocking endpoint above stays available for the existing UI + MCP integrations.

### Request — New Analysis

```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": "ce-imei",
    "requirement": "Add a GSMA Device Check lost/stolen validator to the IMEI validation endpoint"
  }'
```

### Request — Continue with Answers

```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "projectId": "ce-imei",
    "requirement": "Add a GSMA Device Check lost/stolen validator to the IMEI validation endpoint",
    "sessionId": "550e8400-e29b-41d4-a716-446655440000",
    "answers": "Implement as a new screening service. Wire into ValidationService via ImeiValidationFacade. Reject LOST/STOLEN with 422."
  }'
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | No | Which project to analyze against. If omitted, Brain auto-detects the most relevant project using affinity detection. |
| `requirement` | string | Yes | The task, ticket, or question |
| `sessionId` | string | No | Include to continue a previous clarification session |
| `answers` | string | No | Your answers to the Brain's clarification questions |

### Response — Needs Clarification (planReady: false)

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "planReady": false,
  "plan": null,
  "questions": [
    {
      "text": "Should the GSMA check be synchronous or async?",
      "options": ["Synchronous — block until result", "Asynchronous — fire-and-forget with callback"]
    },
    {
      "text": "What HTTP status code for a blocked IMEI — 422 or 403?",
      "options": ["422 Unprocessable Entity", "403 Forbidden", "400 Bad Request"]
    }
  ],
  "unknownReferences": [],
  "dimensions": {
    "why": { "score": 0.9, "summary": "Business need is clear" },
    "what": { "score": 0.7, "summary": "Scope mostly defined" },
    "where": { "score": 0.8, "summary": "IMEI validation endpoint identified" },
    "how": { "score": 0.3, "summary": "Technical approach unclear" }
  },
  "kind": "IMPLEMENT",
  "forcedAfterMaxRounds": false
}
```

### Response — Plan Ready (planReady: true)

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "planReady": true,
  "plan": "{\"requirement\":\"...\",\"understanding\":{...},\"affectedFiles\":[...],\"steps\":[...],\"risks\":[...],\"conventionsApplied\":[...],\"assumptions\":[]}",
  "questions": null,
  "unknownReferences": null,
  "dimensions": null,
  "kind": "IMPLEMENT",
  "forcedAfterMaxRounds": false
}
```

The `plan` field contains a JSON string. When parsed, it has this structure:

```json
{
  "requirement": "Add a GSMA Device Check lost/stolen validator",
  "understanding": {
    "why": "Regulatory compliance for stolen device screening",
    "what": "New screening service + API integration",
    "where": "ImeiValidationFacade → new GSMADeviceCheckService",
    "how": "Follow existing DMDNegativeCheckService pattern"
  },
  "affectedFiles": [
    { "path": "src/main/java/.../GSMADeviceCheckService.java", "reason": "new service", "confidence": 0.95 }
  ],
  "steps": [
    { "order": 1, "description": "Create GSMADeviceCheckService", "convention": "Use @RequiredArgsConstructor [source: CONTRIBUTING.md]", "files": ["GSMADeviceCheckService.java"] }
  ],
  "risks": [
    { "description": "GSMA API latency may exceed timeout", "severity": "medium", "files": ["GSMADeviceCheckService.java"] }
  ],
  "conventionsApplied": [
    { "rule": "Use constructor injection", "source": "CONTRIBUTING.md" }
  ],
  "assumptions": []
}
```

### Response — Explanation (kind: EXPLAIN)

When the requirement is read-only ("explain this project", "describe the auth flow"):

```json
{
  "sessionId": "...",
  "planReady": true,
  "plan": "## Overview\nThis is a Spring Boot application that validates IMEI numbers...\n\n## Key Services\n- `ImeiValidationFacade` orchestrates...",
  "kind": "EXPLAIN",
  "forcedAfterMaxRounds": false
}
```

The `plan` field contains markdown (not JSON) for explain responses.

---

## Async Jobs (AsyncJobService)

Unified API for tracking every heavy operation (ingestion, doc bundle, autodev, Avenger reviews, multi-repo PRs, ticket fan-out, CI remediation, rule-pack install). One job model, one SSE stream, race-free in-flight de-duplication.

### GET /api/v1/jobs/{jobId}

Polling fallback. Returns the current `AsyncJob` snapshot.

```bash
curl http://localhost:8080/api/v1/jobs/8c9a…
```

```json
{
  "id": "8c9a…",
  "jobType": "INGEST_PROJECT",
  "targetKind": "PROJECT",
  "targetId": "ce-imei",
  "projectId": "ce-imei",
  "status": "RUNNING",
  "progressPct": 40,
  "progressMsg": "Embedding 1234 chunks…",
  "startedAt": "2026-04-28T16:21:09Z",
  "finishedAt": null,
  "errorMessage": null,
  "result": null
}
```

Statuses: `QUEUED → RUNNING → SUCCEEDED | PARTIAL | FAILED | CANCELLED`.

### GET /api/v1/jobs/stream/{jobId}

Server-Sent Events stream. Always emits a `snapshot` event on subscribe, then `progress` events as the job advances, then a `succeeded` / `failed` / `partial` terminal event before closing.

```bash
curl -N -H "Accept: text/event-stream" \
  http://localhost:8080/api/v1/jobs/stream/8c9a…
```

```
event: snapshot
data: {"job":{"id":"8c9a…","status":"RUNNING","progressPct":40,…}}

event: progress
data: {"job":{"id":"8c9a…","status":"RUNNING","progressPct":75,"progressMsg":"Persisting graph…"}}

event: succeeded
data: {"job":{"id":"8c9a…","status":"SUCCEEDED","finishedAt":"…","result":"{\"projectId\":\"ce-imei\"}"}}
```

Connection times out after 10 minutes by default (`BRAIN_JOBS_SSE_TIMEOUT_SECONDS`). Heartbeat comment-frames every 15 s keep proxies open. Reconnect with the standard `EventSource` retry; the next subscribe immediately re-sends the current snapshot.

`@PreAuthorize("@projectAccess.canReadJob(#jobId)")` — caller must have read access on the job's `projectId`.

### GET /api/v1/jobs

List recent jobs, optionally scoped to a project. Used by the "Recent Jobs" panel.

```bash
curl "http://localhost:8080/api/v1/jobs?projectId=ce-imei&limit=20"
curl "http://localhost:8080/api/v1/jobs?status=RUNNING&limit=10"
```

### Job types

| `jobType` | Triggered by | Target |
|---|---|---|
| `INGEST_PROJECT` | `POST /projects/ingest` | projectId |
| `FULL_DOC_BUNDLE` | `POST /docs/full/{projectId}` | projectId |
| `RETRY_FAILED_SECTIONS` | `POST /docs/{id}/retry-failed` | documentId |
| `AUTODEV_PIPELINE` | `POST /autodev/execute/start` | sessionId |
| `MULTI_REPO_PR` | `POST /autodev/create-prs/start` | sessionId |
| `AVENGER_FULL_REVIEW` | `POST /avengers/full-review/start` | projectId+code-hash |
| `AVENGER_SINGLE_REVIEW` | `POST /avengers/{name}/review/start` | avenger+projectId+code-hash |
| `TICKET_PROPOSAL` | `POST /jira/tickets/propose` | proposalId |
| `TICKET_CREATION` | `POST /jira/tickets/create` | proposalId or projectKey+count |
| `CI_REMEDIATION` | `POST /webhooks/github` (workflow_run failure) | prRecordId |
| `RULE_PACK_INSTALL` | `POST /projects/{id}/rule-packs/install/start` | projectId+packId+version |
| `ANALYZE_REQUIREMENT` | `POST /analyze/start` | sessionId |

### In-flight de-duplication

A partial unique index `uq_async_jobs_inflight` on `(jobType, targetKind, targetId) WHERE status IN ('QUEUED','RUNNING')` enforces "at most one in-flight job per target." Duplicate clicks always join the existing run — the controller returns the same `jobId` with `attachedToExisting: true`. There is no 409 "duplicate" response on this API; the contract is "join, don't reject."

---

## GET /actuator/health

Standard Spring Boot health check. Used by load balancers and monitoring.

### Request

```bash
curl http://localhost:8080/actuator/health
```

### Response (200 OK)

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "embedding": {
      "status": "UP",
      "details": { "dimensions": 1024, "model": "OllamaEmbeddingModel" }
    },
    "neo4j": { "status": "UP" }
  }
}
```

The `embedding` component is a custom health indicator that calls the active embedding model. If Ollama is not running, this will show `DOWN` with a diagnostic message.

---

## Error Response Format

All error responses follow a consistent format:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Project not found: nonexistent-id",
  "timestamp": "2026-04-12T10:30:00Z"
}
```

| HTTP Status | Meaning |
|-------------|---------|
| 400 | Validation error (missing field, bad format) |
| 404 | Project or session not found |
| 415 | Wrong Content-Type (use `application/json`) |
| 500 | Internal server error (check logs) |

---

## Smart Intake

Three ways to get text into Brain before running an analysis.

### Upload a Document or Image

```
POST /api/v1/intake/upload
Content-Type: multipart/form-data

file=<binary>
userId=default   # optional
```

Accepts PDF, DOCX, TXT, PNG, JPG (up to `BRAIN_INTAKE_MAX_FILE_SIZE_MB`, default 50 MB). Images are processed through OCR. Returns extracted plain text plus an `intakeId` you can reference in subsequent calls.

**Response (200):**

```json
{
  "intakeId": "uuid",
  "sourceType": "DOCUMENT_UPLOAD",
  "fileName": "spec.pdf",
  "extractedText": "...",
  "charCount": 4821
}
```

### Submit Ad-Hoc Text

```
POST /api/v1/intake/adhoc
Content-Type: application/json

{ "text": "As a user I want to..." }
```

**Response (200):** Same shape as upload, `sourceType` is `ADHOC_TEXT`.

### Fetch a Jira Ticket

```
POST /api/v1/intake/jira
Content-Type: application/json

{
  "issueKey": "BRAIN-42",   // or a full Jira URL
  "userId": "default"
}
```

Requires the user to have connected Jira via `/api/v1/auth/jira/connect`. Fetches the ticket, maps its description and acceptance criteria to a requirement string, and saves an intake record.

**Response (200):** Same shape as upload, `sourceType` is `JIRA_TICKET`, plus `issueKey` and `summary` fields.

---

## Document Generation

### Generate a Document

```
POST /api/v1/docs/generate
Content-Type: application/json

{
  "projectId": "ce-imei",
  "prompt": "Write an ADR for adding the GSMA Device Check integration",
  "type": "ADR"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `projectId` | string | Yes | Project to use for RAG context |
| `prompt` | string | Yes | What to generate |
| `type` | string | No | `EXPLANATION`, `HOW_TO`, `ADR`, `API_SPEC`, `CHANGELOG` (default: `EXPLANATION`) |

**Response (200):**

```json
{
  "id": "uuid",
  "title": "Write an ADR for adding the GSMA Device Check...",
  "docType": "ADR",
  "contentMd": "# ADR: GSMA Device Check Integration\n\n## Status\nProposed\n\n..."
}
```

### List Documents for a Project

```
GET /api/v1/docs?projectId=ce-imei
```

Returns all saved documents ordered by creation date descending.

### Get a Document

```
GET /api/v1/docs/{id}
```

### Delete a Document

```
DELETE /api/v1/docs/{id}
```

Returns `204 No Content`.

### Generate Comprehensive Project Documentation PDF

Produces a single PDF covering Architecture, Sequence Flows, Class Model, Process Flows, and Explanation, plus conventions, SLOs, incidents, flaky tests, community summaries, and recent PRs. Mermaid diagrams are pre-rendered server-side via `mmdc`. Cached for `BRAIN_DOCS_CACHE_TTL_MINUTES` (default 60) by content hash.

```
POST /api/v1/docs/full/{projectId}
```

**Response (202):** kicked off async generation.
```json
{ "documentId": "uuid", "status": "GENERATING", "fromCache": false }
```

**Response (200):** cache hit — same content hash within TTL.
```json
{ "documentId": "uuid", "status": "COMPLETED", "fromCache": true, "generatedAt": "2026-04-27T12:34:56Z" }
```

A second concurrent call for the same `projectId` returns the in-flight `documentId` instead of starting a duplicate job.

### Poll Full-Doc Status

```
GET /api/v1/docs/full/status/{documentId}
```

```json
{
  "id": "uuid",
  "projectId": "ce-imei",
  "status": "PARTIAL",
  "sectionResults": { "ARCHITECTURE": "OK", "SEQUENCE_DIAGRAM": "OK", "CLASS_DIAGRAM": "FAILED",
                       "FLOW_DIAGRAM": "OK", "EXPLANATION": "OK" },
  "generatedAt": "2026-04-27T12:34:56Z",
  "cacheHorizon": "2026-04-27T13:34:56Z",
  "fromCache": false,
  "error": null
}
```

Status values: `GENERATING`, `COMPLETED`, `PARTIAL`, `FAILED`.

### Retry Failed Sections

```
POST /api/v1/docs/{documentId}/retry-failed
```

Regenerates the bundle when one or more sections previously failed. 409 if the doc is currently `GENERATING` or already `COMPLETED`.

### Download Full-Doc PDF

```
GET /api/v1/docs/{documentId}/pdf
```

Returns `application/pdf`. Response carries `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`. 404 if status is not `COMPLETED` or `PARTIAL`.

### List Full-Doc History

```
GET /api/v1/docs/full?projectId=ce-imei
```

```json
[ { "id": "uuid", "status": "COMPLETED", "generatedAt": "...", "createdAt": "...", "sizeBytes": 145312 } ]
```

---

## Jira Ticket Creation

### Propose Tickets from Content

```
POST /api/v1/jira/tickets/propose
Content-Type: application/json

{
  "content": "The full requirement or intake extracted text...",
  "projectKey": "BRAIN",
  "userId": "default"
}
```

Brain uses an LLM to decompose the content into a list of actionable Jira tickets (story, task, subtask). Returns proposals for user review before creation.

**Response (200):**

```json
{
  "proposalId": "uuid",
  "projectKey": "BRAIN",
  "count": 3,
  "tickets": [
    { "summary": "Create GSMADeviceCheckService", "type": "Task", "description": "...", "storyPoints": 3 }
  ]
}
```

### Create Tickets in Jira

```
POST /api/v1/jira/tickets/create
Content-Type: application/json

{
  "proposalId": "uuid",   // optional — links back to the proposal
  "projectKey": "BRAIN",
  "userId": "default",
  "tickets": [ { "summary": "...", "type": "Task", "description": "..." } ]
}
```

Requires an active Jira OAuth session for the `userId`. Creates each ticket via the Atlassian REST API.

**Response (200):**

```json
{
  "projectKey": "BRAIN",
  "createdKeys": ["BRAIN-101", "BRAIN-102", "BRAIN-103"],
  "count": 3
}
```

### List Proposals

```
GET /api/v1/jira/tickets?userId=default
```

---

## Jira OAuth

### Start the OAuth Flow

```
GET /api/v1/auth/jira/connect
```

**Response (200):**

```json
{
  "authUrl": "https://auth.atlassian.com/authorize?audience=api.atlassian.com&client_id=...",
  "state": "random-uuid"
}
```

Redirect the user to `authUrl`. After they approve, Atlassian sends a `code` to your `BRAIN_JIRA_OAUTH_REDIRECT_URI`.

### Complete the OAuth Flow

```
POST /api/v1/auth/jira/callback
Content-Type: application/json

{ "code": "auth-code-from-atlassian", "userId": "default" }
```

Exchanges the code for access + refresh tokens, stores them AES-encrypted, and resolves the Jira cloud ID.

**Response (200):**

```json
{ "connected": true, "siteUrl": "https://your-org.atlassian.net", "cloudId": "..." }
```

### Check Connection Status

```
GET /api/v1/auth/jira/status?userId=default
```

**Response (200):**

```json
{ "connected": true, "siteUrl": "https://your-org.atlassian.net", "expiresAt": "2026-05-14T10:00:00Z" }
```

---

---

## Pull Request Creation

### Create PR from Session

```
POST /api/v1/pr/create
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-...",
  "repoUrl": "https://github.com/owner/repo",
  "baseBranch": "main"
}
```

**Response (201):**

```json
{
  "id": "uuid",
  "sessionId": "a1b2c3d4-...",
  "repoUrl": "https://github.com/owner/repo",
  "baseBranch": "main",
  "branchName": "brain/codegen-1713100800000",
  "prNumber": 42,
  "prUrl": "https://github.com/owner/repo/pull/42",
  "status": "CREATED",
  "generatedFiles": { "src/main/java/Foo.java": "..." },
  "selfReviewIterations": 1,
  "errorMessage": null,
  "createdAt": "2026-04-14T10:00:00Z",
  "updatedAt": "2026-04-14T10:01:00Z"
}
```

Session must be COMPLETE with a final plan. Brain generates code via LLM, self-reviews (max 3 iterations), then creates a GitHub Draft PR. Status transitions: GENERATING → REVIEWING → CREATING → CREATED (or FAILED).

### Get PR Details

```
GET /api/v1/pr/{id}
```

### List PRs

```
GET /api/v1/pr
GET /api/v1/pr?sessionId=uuid
```

### Get Self-Review History

```
GET /api/v1/pr/{id}/reviews
```

Returns the self-review iterations with verdict (PASS/FAIL), issues found, and fixes applied.

---

---

## CI Feedback & Learning

### GitHub Webhook

```
POST /api/v1/webhooks/github
X-Hub-Signature-256: sha256=...
X-GitHub-Event: workflow_run
```

Configure in your GitHub repo: Settings → Webhooks → Add webhook. Point to `https://your-brain-host/api/v1/webhooks/github`. Select "Workflow runs" event. Set the secret to match `BRAIN_GITHUB_WEBHOOK_SECRET`.

When CI fails on a `brain/*` branch, Brain automatically parses failures and pushes fixes (max 3 attempts).

### CI Remediation Timeline

```
GET /api/v1/pr/{id}/remediations
```

Returns chronological list of CI fix attempts with status (PENDING, ANALYZING, FIXING, PUSHED, RESOLVED, EXHAUSTED).

### Analyze Merged PR (Trigger Learning)

```
POST /api/v1/pr/{id}/analyze-merge?projectId=proj-1
```

Compares generated code vs. final merged code, adjusts convention trust weights (bounded ±0.1, clamped [0.1, 3.0]).

### Learning Events

```
GET /api/v1/learning/events
GET /api/v1/learning/events?projectId=proj-1
GET /api/v1/learning/events/pr/{prRecordId}
```

Returns convention weight adjustment history with old/new weights and reasons.

---

## Token Usage Monitoring

### Get Token Usage Summary

```
GET /api/v1/monitor/token-usage?hours=24
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `hours` | integer | `24` | Lookback window in hours (1, 24, 168, 720) |

**Response (200):**

```json
{
  "totalInputTokens": 42000,
  "totalOutputTokens": 8500,
  "totalCost": 0.37,
  "totalCalls": 18,
  "cacheHitRate": 0.44,
  "breakdown": [
    {
      "serviceName": "PlannerService",
      "operation": "IMPLEMENT",
      "callCount": 12,
      "inputTokens": 30000,
      "outputTokens": 6000,
      "cost": 0.25
    }
  ]
}
```

Aggregates token usage across all LLM calls within the requested window. `cacheHitRate` is the fraction of calls served from the semantic cache (Redis). `breakdown` groups by service name and operation type.

### Get Recent LLM Calls

```
GET /api/v1/monitor/token-usage/recent?limit=20
```

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | integer | `20` | Maximum number of records to return (1–100) |

**Response (200):**

```json
[
  {
    "id": "uuid",
    "operation": "IMPLEMENT",
    "inputTokens": 2500,
    "outputTokens": 800,
    "latencyMs": 3200,
    "costEstimate": 0.021,
    "cached": false,
    "createdAt": "2026-04-14T10:05:00Z"
  }
]
```

Returns individual LLM call records ordered by `createdAt` descending. `cached: true` means the response was served from the Redis semantic cache — no tokens were consumed for those calls.

---

## Avengers API

### `POST /api/v1/avengers/{name}/review`

Invoke a single Avenger to review code, config, or an artifact. `{name}` is case-insensitive (`stark`, `STARK`, `Stark` all work). Must be a valid `AvengerType`: STARK, HAWKEYE, VISION, WIDOW, HULK, FURY, FORGE, ORACLE, MANTIS, JARVIS, THANOS.

**Request:**
```json
{
  "projectId": "ce-imei",
  "code": "public class Foo { @Autowired private Bar bar; }",
  "context": "optional extra context (plan, requirement, etc.)"
}
```

**Response (200):**
```json
{
  "reviewId": "550e8400-e29b-41d4-a716-446655440000",
  "avenger": "STARK",
  "verdict": "CHANGES_REQUESTED",
  "issues": ["Field injection (@Autowired) detected — use constructor injection"],
  "summary": "STARK found 1 structural issue",
  "latencyMs": 42
}
```

**Verdict values:** `APPROVED`, `CHANGES_REQUESTED`, `BLOCKED`.

**Notes:**
- STARK delegates to `AstValidator` + `ConventionChecker` (zero LLM tokens)
- All other Avengers use LLM with persona prompt + JSON-schema-validated response
- Every review passes through `RailChain` guardrails (prompt injection → HTTP 400)
- Every review persists in `avenger_reviews` + tracks via `TokenUsageTracker`

### `POST /api/v1/avengers/full-review`

Runs all 11 Avengers in parallel on `brainLlmExecutor`. Same request body as single-review.

**Response (200):**
```json
{
  "overallVerdict": "CHANGES_REQUESTED",
  "totalAvengers": 11,
  "approved": 9,
  "changesRequested": 2,
  "blocked": 0,
  "totalLatencyMs": 4250,
  "reviews": [ /* 11 AvengerResponse objects */ ]
}
```

**Overall verdict logic:** `BLOCKED` if any Avenger blocks; else `CHANGES_REQUESTED` if any requests changes; else `APPROVED`.

### `POST /api/v1/avengers/{name}/review/start`

Async variant of `/{name}/review`. Returns `JobStartResponse` (`jobType: "AVENGER_SINGLE_REVIEW"`); the terminal `succeeded` event's `result` carries the `AvengerResponse`. Dedup target = `avenger + ":" + projectId + ":" + sha256(code)`, so duplicate clicks against the same code+avenger join the existing run.

### `POST /api/v1/avengers/full-review/start`

Async variant of `/full-review`. Returns immediately with a `JobStartResponse`; the 11 Avengers run on `brainLlmExecutor` and emit per-Avenger progress events. Dedup is by `projectId + sha256(code + " " + context)` — duplicate clicks against the same code snapshot join the existing run.

**Response (202):** `JobStartResponse` with `jobType: "AVENGER_FULL_REVIEW"`, `streamUrl`, `pollUrl`. Subscribe to `streamUrl` and the terminal `succeeded` event's `result` field carries the full `FullReviewResponse` payload.

### `GET /api/v1/avengers/{name}/history?projectId=X&limit=20`

Returns the most recent reviews for that Avenger on that project, newest first. Default limit 20.

**Response (200):** Array of `AvengerReview` objects with full issue lists, verdict, token counts.

### HAWKEYE ASFF Findings (Wave A4)

```http
GET /api/v1/avengers/hawkeye/findings.asff?projectId={id}&limit=50
```

Returns HAWKEYE security reviews as a Security Hub Finding Format (ASFF v1.0) batch. `limit` is clamped to 200. Severity mapping: `BLOCKED → CRITICAL`, `CHANGES_REQUESTED → HIGH`, `APPROVED → INFORMATIONAL`. ProductArn / AwsAccountId / Region come from `BRAIN_HAWKEYE_*` env vars.

```bash
curl "$BRAIN_URL/api/v1/avengers/hawkeye/findings.asff?projectId=ce-app&limit=50"
```

**Response (200):** `{ "Findings": [ { "SchemaVersion": "2018-10-08", "Severity": {...}, "Workflow": {...}, "Compliance": {...}, ... } ] }`

---

## Convention Rule Packs (Wave A7)

### Install a rule pack

```http
POST /api/v1/projects/{projectId}/rule-packs/install
```

```json
{
  "version": "1.0.0",
  "thanosApproved": true,
  "pack": {
    "id": "spring-boot",
    "version": "1.0.0",
    "description": "Spring Boot conventions",
    "conventions": [
      { "rule": "Use @RequiredArgsConstructor, never @Autowired fields", "category": "DI", "trustWeight": 1.5 },
      { "rule": "Use @Log4j2 not @Slf4j", "category": "LOGGING", "trustWeight": 1.5 }
    ]
  }
}
```

Caps: ≤500 conventions per pack, rule text truncated to 2000 chars, trustWeight clamped to `[0.1, 3.0]`. `thanosApproved=false` → 500. Re-install of same pack/version → 500 (uninstall first).

**Response (200):** `{ "installed": 2, "sourceTag": "rulepack:spring-boot@1.0.0" }`

### Uninstall a rule pack

```http
DELETE /api/v1/projects/{projectId}/rule-packs/{packId}?version=1.0.0
```

**Response (200):** `{ "removed": 2, "sourceTag": "rulepack:spring-boot@1.0.0" }`

---

## Guardrail Errors

When a request is blocked by the guardrail chain (prompt injection, PII detected with `block-on-pii-detection=true`, output schema violation), the API returns **HTTP 400**:

```json
{
  "status": 400,
  "message": "Guardrail INJECTION blocked request: ignore previous instructions",
  "timestamp": "2026-04-16T10:30:00Z"
}
```

The `message` field contains the `RailType` and the violation details. Affected endpoints: `/api/v1/analyze`, `/api/v1/pr/*/review`, `/api/v1/avengers/*`, and any endpoint that invokes an LLM.

For the full guardrail architecture see [Guardrails](GUARDRAILS.md).

---

## Autonomous Development

Six endpoints under `/api/v1/autodev/*` drive the multi-repo autonomous pipeline. Each stage has an explicit approval gate. Full architecture in [Autonomous Development](AUTONOMOUS_DEVELOPMENT.md).

### POST /api/v1/autodev/start

Start a session. Resolves requirement (raw text or IntakeRecord), runs affinity detection + first clarification round.

```bash
curl -sX POST localhost:8080/api/v1/autodev/start \
  -H 'Content-Type: application/json' \
  -d '{"payload":"Add GSMA check to IMEI endpoint","source":"FREE_FORM"}'
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `payload` | string | One of payload/intakeId | Raw requirement text |
| `intakeId` | string (UUID) | | IntakeRecord from `/api/v1/intake/*` |
| `source` | string | | `FREE_FORM`, `JIRA_TICKET`, `PDF_UPLOAD`, etc. |
| `seedProjectId` | string | | Optional seed project |

**200:** `{ sessionId, intakeText, proposedAffectedProjects[], clarificationQuestions[], planReady }`
**400:** Neither payload nor intakeId provided.

### POST /api/v1/autodev/clarify

Submit answers. Session must be `PENDING` or `CLARIFYING`.

```bash
curl -sX POST localhost:8080/api/v1/autodev/clarify \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"...","answers":"Sync; 403 for blocked IMEIs"}'
```

**200:** Same shape as `/start`. When `planReady: true`, proceed to `/plan`.

### POST /api/v1/autodev/plan

Generate multi-repo umbrella plan. Session must be `PENDING` or `CLARIFYING`.

```bash
curl -sX POST localhost:8080/api/v1/autodev/plan \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"..."}'
```

**200:** `{ sessionId, multiRepoPlan }` — umbrella JSON validated against `schemas/multi-repo-plan.json`.
**409:** Session status not `PENDING`/`CLARIFYING`.

### POST /api/v1/autodev/execute

Walk plan graph: propagate dependencies, annotate seams, generate code. Session must be `PLANNED` or `COMPLETE`.

```bash
curl -sX POST localhost:8080/api/v1/autodev/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"...","approvedProjectIds":["backend","frontend"]}'
```

**200:** `{ sessionId, perProject: [{ projectId, fileCount, nodeCount, nodeErrors[] }] }`
**409:** Session not `PLANNED`/`COMPLETE`.

### POST /api/v1/autodev/create-prs

Fan out Draft PRs. Best-effort per repo. Session must be `EXECUTED` or `COMPLETE`.

```bash
curl -sX POST localhost:8080/api/v1/autodev/create-prs \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"...","repos":[{"projectId":"backend","repoUrl":"https://github.com/o/r","baseBranch":"main"}]}'
```

**200:** `{ batchId, overallStatus, totalRepos, succeeded, failed, skipped, perRepo[] }`
**400:** Empty repos list. **409:** Session not `EXECUTED`/`COMPLETE`.

### GET /api/v1/autodev/batch/{batchId}

Poll batch status.

```bash
curl -s localhost:8080/api/v1/autodev/batch/<batchId>
```

**200:** Full `PrBatch` (id, sessionId, overallStatus, totalRepos, succeeded, failed, skipped, timestamps).
**404:** Batch not found.

---

*For setup instructions, see [Getting Started](GETTING_STARTED.md). For configuration, see [Configuration](CONFIGURATION.md).*
