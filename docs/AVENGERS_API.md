# Avengers API

> **Who this document is for:** Developers invoking Avenger reviews programmatically — either via the REST API, the MCP server (Claude Code / Cursor), or backend service composition. Written for someone familiar with REST and MCP.

Each of the 11 Avengers is a callable service, backed by a REST endpoint and exposed as an MCP tool. Every review is persisted in `avenger_reviews` and runs through the `RailChain` for guardrail protection.

---

## Role Taxonomy

From the 2026 multi-agent orchestration survey ([arXiv 2601.13671](https://arxiv.org/html/2601.13671v1)):

| Role | Members | When they run |
|------|---------|---------------|
| **WORKER** | STARK, HAWKEYE, VISION, WIDOW, HULK, FURY | In parallel — domain execution |
| **SERVICE** | FORGE, ORACLE, MANTIS | Invoked by workers on-demand (cross-cutting utilities) |
| **SUPPORT** | JARVIS, THANOS | Last — supervisory / oversight |

`AvengerOrchestrator.runFullReview()` runs all 11 in parallel on the `brain-llm-` thread pool. Total latency ≤ 30s under the LLM budget.

---

## Review Flow

```
Client → POST /api/v1/avengers/{name}/review
        ↓
RailChain.applyPreLlm() [sanitize, mask PII, block injection]
        ↓
AvengerReviewer.review()
├── STARK: delegates to AstValidator + ConventionChecker (zero LLM tokens)
└── Others: loads persona.md, calls ChatModel with strict JSON schema
        ↓
RailChain.applyPostLlm() [validate JSON schema]
        ↓
TokenUsageTracker.track() [ORACLE — accounted]
        ↓
Persist AvengerReview row
        ↓
Response: {reviewId, avenger, verdict, issues[], summary, latencyMs}
```

---

## REST Endpoints

### `POST /api/v1/avengers/{name}/review`

Invoke a single Avenger. `{name}` is case-insensitive and must be a valid `AvengerType` (STARK, HAWKEYE, …).

**Request:**
```json
{
  "projectId": "ce-imei",
  "code": "public class Foo {}",
  "context": "optional extra context"
}
```

**Response (200):**
```json
{
  "reviewId": "550e8400-e29b-41d4-a716-446655440000",
  "avenger": "STARK",
  "verdict": "APPROVED",
  "issues": [],
  "summary": "STARK found no structural issues",
  "latencyMs": 42
}
```

**Verdict values:** `APPROVED`, `CHANGES_REQUESTED`, `BLOCKED`.

**Blocked by guardrails (400):** If `code` contains prompt injection patterns, returns `RailBlockedException`.

### `POST /api/v1/avengers/full-review`

Runs all 11 Avengers in parallel. Same request body as single-review.

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

### `GET /api/v1/avengers/{name}/history?projectId=X&limit=20`

Returns the most recent reviews for that Avenger on that project, newest first. Default limit 20.

### `GET /api/v1/avengers/hawkeye/findings.asff?projectId=X&limit=50` (Wave A4)

HAWKEYE security reviews exported as AWS Security Hub Finding Format v1.0. ProductArn / AwsAccountId / Region come from `BRAIN_HAWKEYE_*` env vars. Severity mapping: `BLOCKED → CRITICAL` (Normalized 90), `CHANGES_REQUESTED → HIGH` (70), `APPROVED → INFORMATIONAL` (0). `limit` clamped to 200.

### `GET /api/v1/avengers/oracle/budget/{projectId}` (Wave A5)

Returns `{recallK, rerankK, tier}` computed from the project's strictest SLO. Tiers: HIGH (≥99.9% target → 200/20), MEDIUM (≥99.5% → 100/10), LOW (<99.5% → 50/5), DEFAULT (no SLO node → 100/10). Used by `PlannerService.retrieveRagContext` to size the Hybrid+Rerank stage.

---

## Avenger upgrades (Wave A)

| Avenger | Wave A capability | Files |
|---------|-------------------|-------|
| HAWKEYE | Findings exportable as ASFF v1.0 (Wave A4) | `avenger/hawkeye/AsffMapper.java`, `rest/v1/avengers/controller/HawkeyeAsffController.java` |
| ORACLE  | SLO-aware token budgeting (Wave A5) — recall/rerank K derived from project's strictest SLO | `avenger/oracle/OraclePromptOptimizer.java`, `graph/repository/SLONodeRepository.java` |
| MANTIS  | `ReviewPattern` adaptive prompt injection (Wave A3) — APPROVED PR-review patterns weighted higher than vanilla `ConventionNode`s | `codegen/AdaptivePromptBuilder.buildReviewPatternSection` |
| STARK   | IntelliJ inspection profiles consumed as `ConventionNode`s (Wave A6) | `ingest/parsers/IntellijInspectionProfileParser.java` |

## Convention Rule Packs (Wave A7)

A rule pack is a versioned bundle of `ConventionNode` definitions installable per project. Conventions get `sourceFile=rulepack:<id>@<version>` so uninstall can target them precisely.

```http
POST /api/v1/projects/{projectId}/rule-packs/install
X-Brain-Approval: <BRAIN_RULEPACK_APPROVAL_TOKEN>
Content-Type: application/json

{
  "version": "1.0.0",
  "pack": {
    "id": "spring-boot",
    "version": "1.0.0",
    "description": "Spring Boot conventions",
    "conventions": [
      { "rule": "Use @RequiredArgsConstructor, never @Autowired fields", "category": "DI", "trustWeight": 1.5 }
    ]
  }
}
```

Caps: ≤500 conventions per pack, rule text truncated to 2000 chars, `trustWeight` clamped to `[0.1, 3.0]`. Missing/mismatched approval header → 403 (fail-closed). `BRAIN_RULEPACK_APPROVAL_TOKEN` empty → all installs/uninstalls rejected.

```http
DELETE /api/v1/projects/{projectId}/rule-packs/{packId}?version=1.0.0
X-Brain-Approval: <token>
```

---

## MCP Tools

12 new MCP tools exposed in [mcp/src/server.ts](../mcp/src/server.ts):

| Tool | Backed By |
|------|-----------|
| `review_with_stark` | `POST /api/v1/avengers/STARK/review` |
| `review_with_hawkeye` | `POST /api/v1/avengers/HAWKEYE/review` |
| `review_with_vision` | `POST /api/v1/avengers/VISION/review` |
| `review_with_widow` | `POST /api/v1/avengers/WIDOW/review` |
| `review_with_hulk` | `POST /api/v1/avengers/HULK/review` |
| `review_with_fury` | `POST /api/v1/avengers/FURY/review` |
| `review_with_forge` | `POST /api/v1/avengers/FORGE/review` |
| `review_with_oracle` | `POST /api/v1/avengers/ORACLE/review` |
| `review_with_mantis` | `POST /api/v1/avengers/MANTIS/review` |
| `review_with_jarvis` | `POST /api/v1/avengers/JARVIS/review` |
| `review_with_thanos` | `POST /api/v1/avengers/THANOS/review` |
| `run_full_avengers_review` | `POST /api/v1/avengers/full-review` |

All take `{ projectId, code, context? }`.

---

## STARK is Special

STARK is the only Avenger that does NOT call the LLM. It delegates to:
- `AstValidator` — syntax, naming conventions, comment detection
- `ConventionChecker` — `@RequiredArgsConstructor`, `@Autowired` field injection, `@Slf4j` vs `@Log4j2`, enum status

Zero LLM tokens, zero latency from model call. Catches structural issues cheaply before any LLM-based Avenger is invoked.

---

## Persona Prompts

Persona content lives in [docs/avengers/](avengers/). Each `.md` file has an `## Output Contract` section specifying the exact JSON structure the LLM must return. The `AvengerPersonaLoader` loads these at startup from the classpath (copied into `build/resources/main/avengers/` by the `copyAvengerPersonas` Gradle task).

To add a new Avenger (rare — consult THANOS first): add an enum value to `AvengerType`, create `docs/avengers/NAME.md`, extend `AVENGER_NAMES` in `mcp/src/avengers.ts`.

---

## Observability

Every review emits:
- `TokenUsageTracker` entry with service `AvengerReviewer.<NAME>` and operation `AVENGER_REVIEW`
- Row in `avenger_reviews` with tokens_in, tokens_out, latency_ms
- Prometheus metric `brain.tokens.used` incremented
- If LLM response fails schema: `brain.guardrail.outcome{rail="SCHEMA",decision="BLOCK"}` increments

---

## Verification

```bash
# Single Avenger
curl -X POST http://localhost:8080/api/v1/avengers/stark/review \
  -H 'Authorization: Bearer <jwt>' \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"ce-imei","code":"public class Foo {}"}'

# Full review
curl -X POST http://localhost:8080/api/v1/avengers/full-review \
  -H 'Authorization: Bearer <jwt>' \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"ce-imei","code":"<file contents>"}'

# History
curl 'http://localhost:8080/api/v1/avengers/stark/history?projectId=ce-imei&limit=10' \
  -H 'Authorization: Bearer <jwt>'
```

---

*For guardrail details see [Guardrails](GUARDRAILS.md). For MCP setup see [MCP Integration](MCP_INTEGRATION.md).*
