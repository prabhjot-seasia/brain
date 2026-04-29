# Configuration

> **Who this document is for:** Developers and DevOps engineers who need to configure Project Brain for different environments — local development, CI/CD, or AWS. Assumes basic familiarity with environment variables and Spring Boot profiles.

---

## Quick Reference

Brain has **zero required API keys** for local development. The default configuration uses Ollama for everything, which runs locally and is free.

---

## Environment Variables

### Core Variables (Always Required)

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_LLM_PROVIDER` | `ollama` | Which LLM to use for chat: `ollama`, `anthropic`, or `bedrock` |
| `BRAIN_EMBED_PROVIDER` | `ollama` | Which model to use for embeddings: `ollama`, `openai`, or `bedrock-titan` |
| `BRAIN_EMBED_MODEL` | `bge-m3` | Embedding model name |
| `BRAIN_EMBED_DIMENSIONS` | `1024` | Vector dimensions (must match the model and the pgvector schema) |

### Database (PostgreSQL)

| Variable | Local Default | Description |
|----------|---------------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://brain-db:5432/brain` | JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | `brain` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `brain_local` | Database password |

In Docker Compose, the database runs as a container named `brain-db`. If running Brain outside Docker (e.g., via `./gradlew bootRun`), change the host to `localhost`:
```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/brain
```

### Database (Neo4j)

| Variable | Local Default | Description |
|----------|---------------|-------------|
| `SPRING_NEO4J_URI` | `bolt://brain-neo4j:7687` | Bolt protocol URI |
| `SPRING_NEO4J_AUTHENTICATION_USERNAME` | `neo4j` | Neo4j username |
| `SPRING_NEO4J_AUTHENTICATION_PASSWORD` | `brain_local` | Neo4j password |

### Ollama (Default Provider)

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_AI_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama API endpoint. In Docker: `http://host.docker.internal:11434` |

No API key needed. Just install Ollama and run `ollama serve`.

### Anthropic (Optional — Direct API)

| Variable | Default | Required When |
|----------|---------|--------------|
| `SPRING_AI_ANTHROPIC_API_KEY` | `not-configured` | `BRAIN_LLM_PROVIDER=anthropic` |

Get a key from [console.anthropic.com](https://console.anthropic.com).

The `not-configured` fallback prevents startup crashes when Anthropic is not the active provider (Spring AI auto-configures the bean regardless).

### OpenAI (Optional — Embeddings Only)

| Variable | Default | Required When |
|----------|---------|--------------|
| `SPRING_AI_OPENAI_API_KEY` | (none) | `BRAIN_EMBED_PROVIDER=openai` |

Get a key from [platform.openai.com/api-keys](https://platform.openai.com/api-keys).

**Warning:** OpenAI `text-embedding-3-small` produces 1536-dim vectors. The default schema is 1024. You need a Liquibase migration to widen the column before using this provider.

### AWS Bedrock (Production)

| Variable | Default | Required When |
|----------|---------|--------------|
| `AWS_ACCESS_KEY_ID` | (none) | `BRAIN_LLM_PROVIDER=bedrock` or `BRAIN_EMBED_PROVIDER=bedrock-titan` |
| `AWS_SECRET_ACCESS_KEY` | (none) | Same |
| `AWS_REGION` | `us-east-1` | Same |
| `BRAIN_LLM_PLAN_MODEL` | `anthropic.claude-sonnet-4-5-v1` | Bedrock model ID for plan generation |
| `BRAIN_LLM_EXTRACT_MODEL` | `anthropic.claude-haiku-4-5-v1` | Bedrock model ID for clarification |

In AWS ECS, these come from the task role (no static keys needed).

### Security (JWT + CORS)

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_JWT_SECRET` | (generated at startup) | HS256 signing key. Must be at least 32 characters. **Set this in production** — without it a new random key is generated on every restart, invalidating all existing tokens. Store in Secrets Manager. |
| `BRAIN_JWT_TTL_MINUTES` | `60` | How long a JWT remains valid after issue. |
| `BRAIN_CORS_ORIGINS` | `http://localhost:3000` | Comma-separated list of origins allowed by CORS. In AWS set this to your CloudFront or ALB URL. |

### GitHub (For Ingestion and Webhooks)

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_GITHUB_TOKEN` | (empty) | Personal Access Token for cloning private repos. Not needed for public repos. |
| `BRAIN_GITHUB_WEBHOOK_SECRET` | (empty) | HMAC secret for verifying GitHub webhook deliveries. Set this to the same value configured in your GitHub repo's webhook settings. Without it, all webhook deliveries are rejected. |

Generate a PAT at [github.com/settings/tokens](https://github.com/settings/tokens) with `repo` scope.

### Jira OAuth (Ticket Creation)

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_JIRA_OAUTH_CLIENT_ID` | (empty) | OAuth 2.0 client ID from [developer.atlassian.com](https://developer.atlassian.com/console/myapps/). Required to connect Jira. |
| `BRAIN_JIRA_OAUTH_CLIENT_SECRET` | (empty) | OAuth 2.0 client secret. Keep in Secrets Manager — never commit. |
| `BRAIN_JIRA_OAUTH_REDIRECT_URI` | `http://localhost:3000/auth/jira/callback` | Where Atlassian redirects after OAuth approval. Must match the callback URL registered in your Atlassian app. |

### Redis Semantic Cache

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_REDIS_HOST` | `localhost` | Redis hostname. Use the Docker service name (`redis`) when running via `docker-compose`. |
| `BRAIN_REDIS_PORT` | `6379` | Redis port. |
| `BRAIN_REDIS_PASSWORD` | (empty) | Redis AUTH password. Leave empty for no-auth local Redis. |
| `BRAIN_CACHE_TTL_SECONDS` | `3600` | How long a cached LLM response is kept before eviction. |
| `BRAIN_CACHE_SIMILARITY_THRESHOLD` | `0.90` | Minimum cosine similarity for a prompt to be considered a cache hit (0.0–1.0). |

### CI Feedback & Learning

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_MAX_REMEDIATION_ATTEMPTS` | `3` | Maximum number of automated CI fix pushes before Brain stops retrying. |
| `BRAIN_LEARNING_WEIGHT_DELTA` | `0.1` | How much to adjust a convention's trust weight per learning event (bounded ±0.1, clamped [0.1, 3.0]). |
| `BRAIN_MIN_TRUST_WEIGHT` | `0.1` | Floor for convention trust weights. Prevents weights from reaching zero. |
| `BRAIN_MAX_TRUST_WEIGHT` | `3.0` | Ceiling for convention trust weights. |

### Spring Cloud Config (AWS Only)

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_CLOUD_CONFIG_URI` | `https://dev-configserver.hyla.hylatest.com` | Config server URL |
| `SPRING_CLOUD_CONFIG_PASSWORD` | (none) | Config server password (from platform team) |

Used only in the `dev` profile. Local profiles use embedded config.

### Sandbox Validation (Wave A2)

Per-plan-node compile + test validation in `EditOrchestrator`. Off by default.

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_SANDBOX_ENABLED` | `false` | When `true`, `SandboxValidationService` writes the per-node aggregated files to a temp dir and runs `gradle compileJava test` / `mvn compile test` / `npm test` per detected build tool. Failures are appended to plan-node errors. |
| `BRAIN_SANDBOX_TIMEOUT_SECONDS` | `600` | Hard wall-clock cap per sandbox run. Exceeded → exitCode=124. |

Path-traversal guards reject keys with `..`/absolute prefixes/null bytes; `SandboxValidationService` drains stdout/stderr concurrently to avoid pipe deadlocks. **Production deployment must isolate the sandbox in Docker (`--network=none`) — see Wave A2 follow-up.** The current implementation uses a host subprocess with cleared env; ship full container isolation before enabling on shared infra.

### HAWKEYE ASFF (Wave A4)

`GET /api/v1/avengers/hawkeye/findings.asff?projectId=...` emits AWS Security Hub Finding Format v1.0.

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_HAWKEYE_ASFF_PRODUCT_ARN` | `arn:aws:securityhub:::product/project-brain/hawkeye` | `ProductArn` stamped onto every finding. Override per AWS account before sending to Security Hub. |
| `BRAIN_HAWKEYE_AWS_ACCOUNT_ID` | `000000000000` | `AwsAccountId` field. Override per environment. |
| `BRAIN_HAWKEYE_REGION` | `us-east-1` | `Resources[].Region` field. |

### Comprehensive Doc Bundle (UX-Q2.2)

`POST /api/v1/docs/full/{projectId}` orchestrates the 5 DocType sections in parallel, stitches a single Markdown bundle, pre-renders Mermaid via `mmdc`, and emits a PDF via Flying Saucer.

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_DOCS_MERMAID_CLI_PATH` | `mmdc` | Path / wrapper command for `@mermaid-js/mermaid-cli`. The Alpine runtime image installs `mmdc-sandboxed` which forwards the `--no-sandbox --disable-setuid-sandbox --disable-dev-shm-usage` puppeteer flags so the headless chromium launched by mmdc can run inside the container. |
| `BRAIN_DOCS_CACHE_TTL_MINUTES` | `60` | Content-hash cache horizon. A second `POST /docs/full/{projectId}` within this window with an unchanged graph fingerprint returns the cached PDF (200 + `fromCache: true`). |
| `BRAIN_DOCS_MAX_PDF_BYTES` | `52428800` (50 MB) | Hard cap on `/docs/{id}/pdf` response. Above this the controller returns 413 instead of streaming the blob. |
| `BRAIN_DOCS_MAX_PRS_INCLUDED` | `20` | How many recent PullRequestRecords are pulled into the bundle's "Recent Pull Requests" section. |
| `BRAIN_DOCS_MERMAID_TIMEOUT_SECONDS` | `30` | Per-diagram wall-clock cap on `mmdc` invocation. Exceeded → diagram falls back to fenced markdown source (best-effort, doesn't fail the bundle). |

The Mermaid SVG injection is post-Markdown via placeholder tokens — commonmark renders with `escapeHtml(true)` and `sanitizeUrls(true)`, and Flying Saucer parses the wrapped XHTML through a hardened `DocumentBuilder` (no DOCTYPE, no external entities, no external DTD, no-op `EntityResolver`) so LLM-supplied markdown can't smuggle XSS or XXE into the PDF.

### Async Jobs + SSE (UX-Q3)

The unified async-job pipeline (every heavy operation streams progress via SSE, dedups in-flight at the DB).

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_JOBS_SSE_HEARTBEAT_MS` | `15000` | Comment-frame heartbeat interval (ms). Keeps SSE proxies and load balancers from idling out long-running jobs. Set to `0` to disable. |
| `BRAIN_JOBS_RETENTION_DAYS` | `90` | Days to keep terminal `async_jobs` rows. Set to `0` to disable purge. |
| `BRAIN_JOBS_RETENTION_CRON` | `0 30 3 * * *` | Spring `@Scheduled` cron expression (server time) for the retention sweep. Default = 03:30 daily. |

### Rule-Pack Approval (Wave A7)

`POST /api/v1/projects/{id}/rule-packs/install` and `DELETE /.../{packId}` require an `X-Brain-Approval` header matching the configured token (THANOS approval).

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_RULEPACK_APPROVAL_TOKEN` | (empty — fail-closed) | Shared secret. Distribute out-of-band only to operators with rule-pack admin rights. Empty value rejects all install/uninstall calls with 403. |

### Spring AI provider keys (workaround)

Spring AI 1.0 auto-configures `anthropicApi` and `openAiAudioSpeechModel` beans regardless of the active provider, and both fail at startup if their respective API key beans are not present. Even when running on Ollama or Bedrock, set:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_AI_ANTHROPIC_API_KEY` | `not-configured` (in `docker/runtime.env`) | Literal placeholder — the bean is never invoked, but Spring AI requires the value to be non-null at startup. |
| `SPRING_AI_OPENAI_API_KEY` | `not-configured` (in `docker/runtime.env`) | Same. |

---

## Spring Profiles

| Profile | How to Activate | What It Does |
|---------|----------------|--------------|
| `local` | Default (no explicit profile needed) | Debug-level logging, auto-pulls Ollama models on startup, embedded database defaults |
| `test` | Activated automatically by `./gradlew test` | Mocked AI beans, in-memory databases via TestContainers, no real LLM calls |
| `dev` | `SPRING_PROFILES_ACTIVE=dev` | Imports Spring Cloud Config Server, routes to Bedrock, connects to external RDS |

### How Profiles Cascade

```
application.yml          ← base config (always loaded)
  └── application-local.yml  ← local overrides (debug logging, Ollama model pull)
  └── application-dev.yml    ← AWS dev overrides (Config Server, Bedrock, external RDS)
  └── application-test.yml   ← test overrides (mocked beans, Ollama auto-config)
```

---

## Tuning Parameters

These are in `application.yml` under the `brain.*` namespace and are rarely changed, but understanding them helps with troubleshooting.

### brain.chunk.*

| Property | Default | What It Controls |
|----------|---------|-----------------|
| `brain.chunk.max-chars` | `18000` | Maximum characters per chunk before splitting. Set below the model's context window (~6K tokens for code at ~3 chars/token). |
| `brain.chunk.overlap-chars` | `200` | Overlap between split sub-chunks. Ensures context continuity across splits. |

If you see "input length exceeds context length" errors, either lower `max-chars` or increase the model's `num-ctx`.

### brain.rag.*

| Property | Default | What It Controls |
|----------|---------|-----------------|
| `brain.rag.top-k-code` | `10` | Number of CODE chunks retrieved per vector search |
| `brain.rag.top-k-doc` | `5` | Number of DOC chunks retrieved per vector search |
| `brain.rag.max-context-tokens` | `4000` | Token budget for RAG context in prompts |
| `brain.rag.doc-trust-weight` | `1.5` | Relevance multiplier for documentation chunks in RAG ranking |
| `brain.rag.code-trust-weight` | `1.0` | Relevance multiplier for code chunks in RAG ranking |
| `brain.rag.max-conventions` | `10` | Maximum conventions injected into LLM prompts |
| `brain.rag.max-graph-classes` | `5` | Maximum graph-context classes injected into LLM prompts |

### brain.clarifier.*

Controls the clarification loop that determines whether Brain has enough context to generate a plan.

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.clarifier.confidence-average-threshold` | `0.75` | `BRAIN_CLARIFIER_AVG_THRESHOLD` | Average across WHY/WHAT/WHERE/HOW must reach this to proceed |
| `brain.clarifier.confidence-per-dimension-floor` | `0.5` | `BRAIN_CLARIFIER_DIM_FLOOR` | Every individual dimension must be above this floor |
| `brain.clarifier.first-round-min-questions` | `3` | `BRAIN_CLARIFIER_FIRST_ROUND_MIN_Q` | Minimum questions the LLM must ask on the first round |
| `brain.clarifier.max-questions-per-round` | `5` | `BRAIN_CLARIFIER_MAX_Q_PER_ROUND` | Maximum questions per clarification round |
| `brain.clarifier.max-rounds` | `5` | `BRAIN_CLARIFIER_MAX_ROUNDS` | Maximum clarification rounds before force-promoting to planning |

### brain.learning.*

Controls the convention learning and adaptive prompt system.

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.learning.max-events-to-scan` | `200` | `BRAIN_LEARNING_MAX_EVENTS` | How many learning events to scan for violation patterns |
| `brain.learning.top-violations-count` | `5` | `BRAIN_LEARNING_TOP_VIOLATIONS` | Number of top violations to surface in memory snapshots |
| `brain.learning.recent-issues-count` | `10` | `BRAIN_LEARNING_RECENT_ISSUES` | Number of recent issues to include in memory |
| `brain.learning.violation-threshold` | `3` | `BRAIN_LEARNING_VIOLATION_THRESHOLD` | How many violations before a convention gets reinforced in prompts |
| `brain.learning.max-diff-chars` | `10000` | `BRAIN_LEARNING_MAX_DIFF_CHARS` | Maximum diff characters analyzed during merge learning |
| `brain.learning.max-few-shot-examples` | `3` | `BRAIN_LEARNING_MAX_FEW_SHOT` | Maximum solution patterns injected as few-shot examples |
| `brain.learning.convention-key-max-length` | `30` | `BRAIN_LEARNING_CONVENTION_KEY_MAX` | Truncation length for unrecognized convention keys |

### brain.codegen.*

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.codegen.small-change-max-files` | `3` | `BRAIN_CODEGEN_SMALL_CHANGE_MAX_FILES` | File count threshold for diff-based vs full code generation |

### brain.llm.chars-per-token

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.llm.chars-per-token` | `4.0` | `BRAIN_LLM_CHARS_PER_TOKEN` | Character-to-token ratio for budget estimation |

### brain.security (Rate Limiting)

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.security.llm-rate-limit-per-minute` | `10` | `BRAIN_SECURITY_LLM_RATE_LIMIT` | Max LLM endpoint calls per user per minute |
| `brain.security.api-rate-limit-per-minute` | `60` | `BRAIN_SECURITY_API_RATE_LIMIT` | Max general API calls per user per minute |
| `brain.security.rate-limit-window-ms` | `60000` | `BRAIN_SECURITY_RATE_LIMIT_WINDOW_MS` | Rate limit sliding window duration |

### brain.ci.max-failure-log-chars

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.ci.max-failure-log-chars` | `15000` | `BRAIN_CI_MAX_LOG_CHARS` | Maximum CI log characters parsed for failure analysis |

### brain.cache.pattern-cache-ttl-hours

| Property | Default | Env Var | What It Controls |
|----------|---------|---------|-----------------|
| `brain.cache.pattern-cache-ttl-hours` | `24` | `BRAIN_CACHE_PATTERN_TTL_HOURS` | TTL for solution pattern cache entries |

### Ollama Model Options

| Property | Default | What It Controls |
|----------|---------|-----------------|
| `spring.ai.ollama.chat.options.num-ctx` | `16384` | Context window for chat model. Ollama defaults to 2048 which is too small. |
| `spring.ai.ollama.chat.options.num-predict` | `6144` | Max output tokens for chat. Low values cause JSON truncation. |
| `spring.ai.ollama.embedding.options.num-ctx` | `8192` | Context window for embedding model. Must be >=8192 for bge-m3. |

### Anthropic Options

| Property | Default | What It Controls |
|----------|---------|-----------------|
| `spring.ai.anthropic.chat.options.max-tokens` | `8192` | Max output tokens. Too low = truncated JSON plans. |

---

## Environment File Setup

### For Local Development

```bash
# .env (read by build-and-deploy.sh)
BRAIN_LLM_PROVIDER=ollama
BRAIN_EMBED_PROVIDER=ollama
BRAIN_EMBED_MODEL=bge-m3
BRAIN_EMBED_DIMENSIONS=1024
SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434

# docker/runtime.env (read by the brain-app container)
# Same values — copy .env here
```

### For AWS Dev

The `dev` profile imports credentials from Spring Cloud Config Server. The only env vars set on the ECS task definition are:

```
SPRING_PROFILES_ACTIVE=dev
SPRING_CLOUD_CONFIG_URI=https://dev-configserver.hyla.hylatest.com
SPRING_CLOUD_CONFIG_PASSWORD=<from Secrets Manager>
AWS_REGION=us-east-1
```

Everything else (DB credentials, Neo4j credentials, Bedrock model IDs) comes from the Config Server.

---

## Logging

Brain uses **Log4j2 with YAML configuration** (not Logback — Logback is explicitly excluded in `build.gradle`).

The config file is `src/main/resources/log4j2.yaml`.

| Profile | Log Level | Format |
|---------|-----------|--------|
| local | DEBUG | Human-readable console output |
| dev | INFO | JSON (ships to CloudWatch) |
| test | WARN | Minimal console output |

---

## Health Checks & Observability

The `/actuator/health` endpoint reports three components:

| Component | What It Checks |
|-----------|---------------|
| `db` | PostgreSQL connectivity (standard Spring Boot) |
| `neo4j` | Neo4j Bolt connectivity (Spring Data Neo4j) |
| `embedding` | Calls the active embedding model with a test input. Reports UP with dimension count, or DOWN with the error. |

The `embedding` indicator is custom (`EmbeddingHealthIndicator.java`) and is the first thing to check when ingestion fails — it tells you immediately if Ollama is unreachable or the model isn't pulled.

### Prometheus Metrics

`/actuator/prometheus` exposes Micrometer metrics in Prometheus scrape format. Available metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `brain_tokens_used_total` | Counter | Total estimated LLM tokens consumed |
| `brain_cache_hits_total` | Counter | Semantic cache hits (LLM call avoided) |
| `brain_cache_misses_total` | Counter | Semantic cache misses (LLM call made) |
| `brain_ast_validations_total` | Counter | AST validations performed on generated code |
| `brain_ast_failures_total` | Counter | AST validations that failed |
| `brain_pr_created_total` | Counter | Pull requests created successfully |
| `brain_pr_failed_total` | Counter | Pull requests that failed |
| `brain_llm_latency_seconds` | Timer (histogram) | LLM call latency with p50/p95/p99 percentiles |
| `brain_guardrail_outcome_total` | Counter (tagged: `rail`, `decision`) | Guardrail rail evaluations by type and decision (PASS/BLOCK/MODIFY) |
| `brain_avenger_memory_hits_total` | Counter (tagged: `avenger`) | AvengerMemory Redis cache hits per Avenger |
| `brain_avenger_memory_misses_total` | Counter (tagged: `avenger`) | AvengerMemory rebuilds from DB per Avenger |

Actuator security: `/actuator/health` and `/actuator/info` are public. All other actuator endpoints (including `/actuator/prometheus`) are denied on the main API port. For production Prometheus scraping, expose actuator on a separate management port:

```yaml
management:
  server:
    port: 8081
```

### Token Cost Tracking

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_COST_INPUT_TOKEN_RATE` | `0.000003` | Cost per input token (USD) |
| `BRAIN_COST_OUTPUT_TOKEN_RATE` | `0.000015` | Cost per output token (USD) |

Token usage dashboard available at `/tokens` in the admin UI. API: `GET /api/v1/monitor/token-usage`.

### Response Headers

All API responses include:
- `X-Correlation-ID` — request correlation ID (propagated from client or auto-generated UUID)
- `X-API-Version` — API version (`v1`)

### Guardrails

Input and output guardrails applied to every LLM call. Full architecture in [Guardrails](GUARDRAILS.md).

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_GUARDRAILS_MAX_INPUT_CHARS` | `20000` | Max length of user input before `InputLengthRail` blocks (PRE_LLM) |
| `BRAIN_GUARDRAILS_MAX_PROMPT_CHARS` | `50000` | Max length of assembled prompt |
| `BRAIN_GUARDRAILS_MAX_OUTPUT_CHARS` | `30000` | Max length of LLM response before `OutputLengthRail` blocks (POST_LLM) |
| `BRAIN_GUARDRAILS_BLOCK_ON_PII` | `false` | If `true`, `PiiMaskRail` BLOCKs requests containing PII instead of masking (MODIFY) |
| `BRAIN_GUARDRAILS_STRICT_SCHEMA` | `true` | If `true`, `OutputSchemaRail` rejects LLM responses that fail JSON schema validation |
| `BRAIN_GUARDRAILS_INJECTION_CLASSIFIER` | `REGEX` | `REGEX` (default) or `ONNX` (future — StackOne Defender model) |

Custom prompt-injection patterns live in `src/main/resources/guardrails/prompt-injection-patterns.txt` (one regex per line, `#` comments allowed).

### Avenger Memory

Per-Avenger semantic memory cached in Redis. Full architecture in [Avenger Memory](AVENGER_MEMORY.md).

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_CACHE_AVENGER_MEMORY_TTL_HOURS` | `24` | Redis cache TTL for `AvengerMemorySnapshot` |
| `BRAIN_CACHE_MAX_MEMORY_HINT_TOKENS` | `300` | Max tokens injected into Avenger prompts from memory (ORACLE-enforced cap) |

### Autonomous Development

Multi-repo requirement intake, project affinity detection, and plan orchestration. Full architecture in [Autonomous Development](AUTONOMOUS_DEVELOPMENT.md).

| Variable | Default | Description |
|----------|---------|-------------|
| `BRAIN_AUTODEV_CONFIDENCE_THRESHOLD` | `0.7` | Per-project confidence floor from `ProjectAffinityDetector`. Below this, user is prompted to confirm. |
| `BRAIN_AUTODEV_MAX_PROJECTS` | `5` | Hard cap on affected projects per autonomous flow. Prevents token-budget blowouts when the LLM over-spreads. |
| `BRAIN_AUTODEV_MAX_PLAN_NODES` | `50` | Hard cap on plan-graph nodes (seed + derived edits) per session. Prevents runaway dependency propagation. |
| `BRAIN_AUTODEV_BLAST_RADIUS_THRESHOLD` | `10` | `SeamAnalyzer` flags edits whose dependents exceed this count as `HIGH_BLAST_RADIUS` (user-visible warning, non-blocking). |

---

*For deployment, see [DevOps Runbook](DEVOPS_RUNBOOK.md). For architecture, see [Architecture](ARCHITECTURE.md).*
