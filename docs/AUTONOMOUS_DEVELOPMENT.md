# Autonomous Development

> **Who this document is for:** Engineers and technical leads evaluating or using Brain's autonomous multi-repo development pipeline. Assumes familiarity with Brain's single-repo flow ([Architecture](ARCHITECTURE.md)) and with GitHub PR mechanics.

---

## Overview

Brain's autonomous development pipeline turns a requirement (Jira ticket, pasted spec, uploaded PDF) into one or more **draft** GitHub pull requests across the affected repositories. Four explicit stages, four human approval gates.

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 1 — Intake & Affinity                                               │
│  Input: raw text, Jira key, PDF/DOCX/image upload, or IntakeRecord id      │
│  Output: session with requirement_text + proposed affectedProjects         │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │  (user confirms which projects are in scope)
               ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 2 — Clarification loop                                              │
│  ClarifierService iterates up to 3 rounds on WHY/WHAT/WHERE/HOW axes       │
│  Output: session.rounds, confident=true or force-promote after max rounds  │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │
               ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 3 — Multi-repo plan                                                 │
│  PlannerService.generateMultiRepoPlan fans out over affected repos on      │
│    brainLlmExecutor → umbrella JSON schema-validated via OutputSchemaRail  │
│  Output: session.finalPlan = umbrella plan (per-project plan + status)     │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │  (user reviews + approves plan)
               ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 4 — Execute (edit orchestration)                                    │
│  EditOrchestrator walks per-project PlanGraph topologically:               │
│    DependencyPropagationAnalyzer → derived edits from seed edits           │
│    SeamAnalyzer → SAFE_SEAM / NEEDS_CHARACTERIZATION_TEST / HIGH_BLAST…    │
│    CodeGeneratorService.generateCode per node, files aggregated per repo   │
│  Output: session.planGraphJson persisted; per-project file maps in memory  │
└──────────────┬─────────────────────────────────────────────────────────────┘
               │  (optional: second approval gate if HIGH_BLAST_RADIUS)
               ▼
┌────────────────────────────────────────────────────────────────────────────┐
│  Stage 5 — Draft PRs (parallel, best-effort)                               │
│  MultiRepoPrOrchestrator fans out on brainLlmExecutor:                     │
│    SelfReviewLoop → PrCreationService.createPullRequest per repo           │
│  One repo fails → batch marked PARTIAL, surviving PRs still drafted        │
│  Output: PrBatch {batchId, overallStatus, perRepo[]}                       │
└────────────────────────────────────────────────────────────────────────────┘
```

## Quick start (curl)

```bash
# 1. Start the autodev session with a raw requirement
curl -sX POST localhost:8080/api/v1/autodev/start \
  -H 'Content-Type: application/json' \
  -d '{"payload":"Add a GSMA check to the IMEI endpoint","source":"FREE_FORM"}'
# → { sessionId, intakeText, proposedAffectedProjects, clarificationQuestions, planReady }

# 2. Answer clarifying questions (repeat until planReady:true)
curl -sX POST localhost:8080/api/v1/autodev/clarify \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"…","answers":"Sync; 403 for blocked IMEIs"}'

# 3. Generate the multi-repo plan (umbrella JSON)
curl -sX POST localhost:8080/api/v1/autodev/plan \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"…"}'

# 4. Execute the plan graph → per-project file maps + plan_graph_json persisted
curl -sX POST localhost:8080/api/v1/autodev/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"…"}'

# 5. Fan out PRs
curl -sX POST localhost:8080/api/v1/autodev/create-prs \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"…","repos":[
        {"projectId":"backend","repoUrl":"https://github.com/o/backend","baseBranch":"main"},
        {"projectId":"frontend","repoUrl":"https://github.com/o/frontend","baseBranch":"main"}
      ]}'

# 6. Poll the batch
curl -s localhost:8080/api/v1/autodev/batch/<batchId>
```

## REST endpoints

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/autodev/start` | Create session, run affinity + first clarification round |
| `POST /api/v1/autodev/clarify` | Submit answers, run next clarification round |
| `POST /api/v1/autodev/plan` | Generate multi-repo umbrella plan (sets `session.finalPlan`) |
| `POST /api/v1/autodev/execute` | Walk plan graph, generate code, persist `plan_graph_json` |
| `POST /api/v1/autodev/create-prs` | Fan out PR creation; returns `MultiRepoPrResult` with `batchId` |
| `GET  /api/v1/autodev/batch/{batchId}` | Poll batch roll-up status |

## MCP tools

Six tools expose the same pipeline to Claude Code / Cursor:

- `start_autonomous_development`
- `clarify_autonomous_development`
- `plan_autonomous_development`
- `execute_autonomous_development`
- `create_autonomous_prs`
- `get_autonomous_batch_status`

## UI

Admin UI wizard at [`/autodev`](http://localhost:3000/autodev) walks the same 5 stages with explicit approval buttons between each.

## Tuning

All limits are in `application.yml` under `brain.autodev.*` (see [Configuration](CONFIGURATION.md)):

- `confidence-threshold` (default `0.7`) — per-project affinity floor
- `max-projects` (default `5`) — hard cap on affected repos per autonomous flow
- `max-plan-nodes` (default `50`) — hard cap on plan-graph nodes (seed + derived edits)
- `blast-radius-threshold` (default `10`) — dependents count above which `SeamAnalyzer` raises `HIGH_BLAST_RADIUS`

## Persistence

| Column | Type | Purpose |
|--------|------|---------|
| `clarification_sessions.affected_projects` | `JSONB` | Proposed per-repo affinity |
| `clarification_sessions.plan_graph_json` | `JSONB` | Persisted plan graphs keyed by projectId |
| `pr_batch` (table) | — | Batch roll-up: `overall_status`, `total_repos`, `succeeded`, `failed`, `skipped` |
| `pull_request_records.batch_id` | `uuid` | Links a PR to its batch |
| `pull_request_records.project_id` | `varchar(255)` | Which indexed project this PR targets |
| `pull_request_records.failure_stage` | `varchar(32)` | `PLAN_PARSE` / `CODE_GEN` / `SELF_REVIEW` / `PR_CREATE` |

All migrations are in `db/changelog/versions/2026/26.16/ddl_changelog.yaml`. Each changeset is idempotent (`MARK_RAN` preCondition).

## Failure isolation

Each stage fails independently:

| Failure | Consequence |
|---------|-------------|
| Affinity detector LLM error | Session falls back to single-project mode |
| Clarifier rounds exceed max (3) | Planner is force-promoted with thinner context |
| `EditOrchestrator` per-node codegen failure | Captured in `EditOrchestrationResult.nodeErrors`; other nodes continue |
| Per-repo PR creation failure | Batch marked `PARTIAL`, successful repos still drafted, failure stage captured on `PullRequestRecord.failure_stage` |

## Related docs

- [Architecture](ARCHITECTURE.md) — system design, graph schema
- [Configuration](CONFIGURATION.md) — all `BRAIN_AUTODEV_*` env vars
- [API Reference](API_REFERENCE.md) — full request/response schemas
- [MCP Integration](MCP_INTEGRATION.md) — MCP server setup
- [Planned Features](PLANNED_FEATURES.md) — upcoming capabilities
