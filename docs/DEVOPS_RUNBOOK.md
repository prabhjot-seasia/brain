# Project Brain — DevOps Deployment Runbook

> **Who this document is for:** DevOps engineers and platform team members deploying Project Brain to AWS. Written for someone comfortable with AWS console, GitHub Actions, and CDK. Follow the steps in order.

All infrastructure and application deploys run via GitHub Actions.
No local AWS tooling required beyond optional `aws` CLI for verification.

For architecture details, cost estimates, and teardown: see [SETUP_AWS.md](SETUP_AWS.md).

---

## Pre-AWS Deploy Gate (THANOS-enforced)

**Run every step. Every step must be green. Skipping is BLOCKED per `docs/avengers/THANOS.md` § 2.4.**

### Gate 1 — Code is correct

```bash
# Backend full test suite (uses TestContainers; no external DB required)
./gradlew test                    # expect: 1080+ tests, 0 failures

# Frontend full test suite
cd ui && npx vitest run           # expect: 128+ tests, 0 failures
```

### Gate 2 — Stack starts cleanly with the artifact you're deploying

```bash
./build-and-deploy.sh up          # builds bootJar + image, brings up db + neo4j + redis + app + Vite UI
curl -sf http://localhost:8080/actuator/health   # expect: {"status":"UP"}
curl -sf http://localhost:3000/                  # expect: HTTP 200
```

### Gate 3 — User-facing surface is clean (real Chrome, no headless)

```bash
./gradlew bddUi -Dbrain.ui.headless=false        # expect: 172 tests, 0 failures
```

This drives a visible Chrome through every page. The `@After("@ui")` hook auto-asserts no `5xx` response in the browser console — any new 500-bleed-to-UI fails the gate.

### Gate 4 — DB-layer smokes against the running Postgres

See [§ Async-jobs smoke checklist](#async-jobs-smoke-checklist-ux-q3) below. All four DB checks must pass:

1. `unique_violation` raised on duplicate in-flight insert (constraint `uq_async_jobs_inflight`)
2. Dedup lookup `EXPLAIN ANALYZE` shows `Index Scan using uq_async_jobs_inflight`
3. Retention purge `EXPLAIN ANALYZE` shows `Index Scan using idx_async_jobs_terminal_finished`
4. Post-terminal re-queue inserts a fresh row (join-don't-reject contract)

### Gate 5 — Cloud config has the prod values, not soft-mode defaults

In `gl-dls-ce-config-files/project-brain.yml`, verify:

```yaml
brain:
  security:
    enforce-project-membership: ${BRAIN_SECURITY_ENFORCE_PROJECT_MEMBERSHIP:true}   # MUST be true in prod
  jobs:
    sse-heartbeat-ms: 15000
    retention-days: 90
    retention-cron: 0 30 3 * * *
```

If `enforce-project-membership` is `false` or missing in prod config, **stop**. Prod must enforce membership.

### Gate 6 — Secrets are present, not "not-configured"

```bash
# In the deploy environment (NOT in this repo):
echo "$BRAIN_JWT_SECRET" | wc -c              # expect: > 32 chars
echo "$BRAIN_GITHUB_WEBHOOK_SECRET" | wc -c   # expect: > 32 chars
echo "$BRAIN_TOKEN_ENCRYPTION_KEY" | wc -c    # expect: 32 (AES-256 key)
echo "$BRAIN_GITHUB_TOKEN" | wc -c            # expect: ~ 40 (PAT)
```

Empty or "not-configured" values mean security is degraded. Do not deploy.

### Gate 7 — Cleanup before deploy

```bash
docker compose down               # tear down local stack
./gradlew clean                   # clean local build artifacts (CI builds fresh)
```

**If all 7 gates are green, you are clear to deploy. Continue with the deploy steps below.**

---

## Pre-flight values

| Item | Value |
|---|---|
| AWS account | `572232158477` |
| Region | `us-east-1` |
| VPC | `vpc-0ba88ffbf31193b38` |
| Subnets | `10.11.57.0/25` (rtb-09f4c13b26536b127), `10.11.57.128/25` (rtb-0eb534b06dfa25122) |
| ACM cert | `arn:aws:acm:us-east-1:572232158477:certificate/c6ecb475-6178-41b1-ab6f-e45a99d643f6` |
| Domain | `dev.hyla.hylatest.com` (context path: `/project-brain-backend`) |
| OIDC role | `DLS_CE_CODEARTIFACT_AWS_ROLE` (existing, needs ECR/ECS/CDK/Bedrock perms) |
| Config server | `https://dev-configserver.hyla.hylatest.com` |
| RDS | External (platform-managed), DB = `brain_dev` |
| Spring profile | `dev` |
| CodeArtifact | domain: `dls`, repo: `main`, namespace: `com.assurant` |

---

## Step 1 — Platform team asks (one-time)

### 1.1 — Add permissions to the existing OIDC role

The role referenced by `DLS_CE_CODEARTIFACT_AWS_ROLE` needs these additional permissions for project-brain:

```
ecr:GetAuthorizationToken, ecr:BatchCheckLayerAvailability, ecr:PutImage,
ecr:InitiateLayerUpload, ecr:UploadLayerPart, ecr:CompleteLayerUpload,
ecr:DescribeImages, ecr:BatchGetImage
ecs:UpdateService, ecs:DescribeServices, ecs:RegisterTaskDefinition
elbv2:DescribeLoadBalancers
cloudformation:* (for CDK deploys)
ec2:DescribeVpcs, ec2:DescribeSubnets, ec2:DescribeSecurityGroups,
ec2:CreateSecurityGroup, ec2:AuthorizeSecurityGroupIngress,
ec2:AuthorizeSecurityGroupEgress, ec2:CreateSubnet, ec2:RunInstances
iam:CreateRole, iam:PutRolePolicy, iam:PassRole, iam:AttachRolePolicy
secretsmanager:CreateSecret, secretsmanager:GetSecretValue
logs:CreateLogGroup, logs:CreateLogStream, logs:PutLogEvents
bedrock:InvokeModel, bedrock:InvokeModelWithResponseStream
ssm:DescribeInstanceInformation (for CDK VPC lookups)
```

### 1.2 — Create database

```sql
-- On the existing RDS instance
CREATE DATABASE brain_dev;
\c brain_dev
CREATE EXTENSION vector;
```

### 1.3 — Register on Spring Cloud Config Server

Create `project-brain-dev.yml` on the config server with:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<rds-host>.rds.int:5432/brain_dev
    username: brain
    password: <db-password>
```

Neo4j connection details will be added after Step 2 deploys the Neo4j EC2 instance.

### 1.4 — CDK bootstrap (if not already done)

```bash
cdk bootstrap aws://572232158477/us-east-1
```

### 1.5 — Verify GitHub secrets

These secrets must exist in the GitHub repo (org-level, shared with event-manager):

| Secret | Expected value |
|---|---|
| `DLS_CE_CODEARTIFACT_AWS_ACCOUNT` | `572232158477` |
| `DLS_CE_CODEARTIFACT_AWS_ROLE` | Role name from 1.1 |
| `DLS_CE_CODEARTIFACT_AWS_REGION` | `us-east-1` |
| `DLS_CE_CODEARTIFACT_URL` | CodeArtifact repository URL |

---

## Step 2 — Deploy infrastructure (~15 min)

1. Push code to `main`
2. GitHub Actions UI → **CDK Infrastructure** → `environment=dev`, `stack=all`, `image_tag=0.1.0`

**EXPECT:** All stacks succeed: `CDK-BRAIN-ECR`, `CDK-DEV-BRAIN-NETWORK`, `CDK-DEV-BRAIN-NEO4J`, `CDK-DEV-BRAIN-ECS`

3. Note the Neo4j private IP from the CDK outputs
4. Update the Config Server: add `spring.neo4j.uri=bolt://<neo4j-private-ip>:7687` and Neo4j credentials (from Secrets Manager secret `dev-brain/neo4j/master`) to `project-brain-dev.yml`

---

## Step 3 — Publish + deploy the app (~10 min)

1. GitHub Actions UI → **Publish to CodeArtifact + ECR** → `version=0.1.0`

**EXPECT:** JAR in CodeArtifact, Docker image `project-brain:0.1.0` in ECR

2. GitHub Actions UI → **Deploy to ECS** → `environment=dev`, `version=0.1.0`

**EXPECT:** Workflow succeeds, smoke test shows `/project-brain-backend/actuator/health` → UP

---

## Step 4 — DNS + verification (5 min)

1. Create Route53 ALIAS record:

```
dev.hyla.hylatest.com → <ALB DNS name from CDK output> (app at /project-brain-backend)
```

2. Verify:

```bash
curl -sf https://dev.hyla.hylatest.com/project-brain-backend/actuator/health | jq
# EXPECT: status=UP, embedding=UP (dimensions=1024)
```

3. Ingest and analyze:

```bash
curl -X POST https://dev.hyla.hylatest.com/project-brain-backend/api/v1/projects/ingest \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"ce-imei","projectName":"ce-IMEI","repoUrl":"https://github.com/assurant/gl-dls-ce-imei.git","branch":"master"}'

curl -sf https://dev.hyla.hylatest.com/project-brain-backend/api/v1/projects/ce-imei/status | jq

# Analyze
curl -X POST https://dev.hyla.hylatest.com/project-brain-backend/api/v1/analyze \
  -H 'Content-Type: application/json' \
  -d '{"projectId":"ce-imei","requirement":"Add a GSMA Device Check lost/stolen validator"}'
# EXPECT: planReady=true, structured JSON plan in <30 seconds
```

---

## Ongoing deploys

```
1. Push code changes to main
2. GitHub UI → Publish → version=X.Y.Z
3. GitHub UI → Deploy to ECS → environment=dev, version=X.Y.Z
```

---

## Troubleshooting

| Symptom | Check |
|---|---|
| CDK workflow fails "no credentials" | OIDC role permissions (Step 1.1) |
| `cdk synth` fails "Cannot do VPC lookup" | Role needs `ec2:DescribeVpcs` + `ssm:*` |
| ECS task crashes `AccessDeniedException` | Bedrock model access not enabled in console |
| `/actuator/health` UP but `embedding` DOWN | model-access not enabled OR wrong `AWS_REGION` |
| App boots but DB connection refused | Config Server not serving `spring.datasource.*` |
| Liquibase fails on `CREATE EXTENSION` | pgvector not enabled on RDS (Step 1.2) |
| Plan generation slow (>30s) | Bedrock throttling — check CloudWatch |
| Neo4j connection refused | Config Server `spring.neo4j.uri` not updated (Step 2, substep 4) |
| Full-doc PDF stuck at PARTIAL with all sections OK | mermaid render failed silently — check container logs for `mmdc` errors. Confirm `mmdc-sandboxed --version` resolves and chromium is on `PATH`. |
| Container logs `Failed to launch the browser process! ... No usable sandbox!` | `mmdc` is using vanilla `mmdc` instead of the wrapper. Verify `BRAIN_DOCS_MERMAID_CLI_PATH=mmdc-sandboxed` is set on the container. |
| Full-doc PDF download returns 404 | Document status is `GENERATING` or `FAILED` — only `COMPLETED`/`PARTIAL` rows ship a PDF blob. Poll `/docs/full/status/{id}`. |

## Comprehensive Doc PDF runtime dependencies

The runtime image ([Dockerfile](../Dockerfile)) is `eclipse-temurin:21-jre-alpine` with three apk packages added on top of the JRE:

- `nodejs` + `npm` — host for `@mermaid-js/mermaid-cli` (`mmdc`).
- `chromium` (multi-arch native — works on both amd64 and Apple-Silicon arm64) — headless browser puppeteer drives.
- `ttf-freefont` — fallback fonts so mermaid SVGs don't render as tofu.

Two files are baked at build time:

- `/etc/mmdc/puppeteer-config.json` — `{"args":["--no-sandbox","--disable-setuid-sandbox","--disable-dev-shm-usage"]}`. Needed because chromium's user-namespace sandbox can't open inside an unprivileged container.
- `/usr/local/bin/mmdc-sandboxed` — one-liner shell wrapper that forwards `-p /etc/mmdc/puppeteer-config.json` to the real `mmdc`. The Spring app calls this wrapper via `BRAIN_DOCS_MERMAID_CLI_PATH=mmdc-sandboxed`.

Env vars the container respects:

- `PUPPETEER_SKIP_DOWNLOAD=true` — block puppeteer from grabbing its own bundled chromium at install time (we use the apk one).
- `PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser` — point puppeteer at the apk binary.
- `BRAIN_DOCS_MERMAID_CLI_PATH=mmdc-sandboxed` — tells `MermaidPreRenderer` which command to exec (default `mmdc` works on dev machines without the wrapper).
- `BRAIN_DOCS_CACHE_TTL_MINUTES=60` — bundle-cache TTL.

If a graphics dependency goes missing post-deploy, the symptom is the architecture section's diagram appearing as a fenced ` ```mermaid ` source block in the PDF instead of an SVG (the renderer falls back to fenced source on any `mmdc` failure). Check container logs for `mermaid-cli exit=` debug lines.

## Async-jobs smoke checklist (UX-Q3)

Reproducible end-to-end smokes for the unified async-job pipeline. Run after every deploy that touches `src/main/java/com/assurant/brain/jobs/**` or any heavy-op service. The DB-layer smokes can run against a local `docker compose up -d db` without the full app — useful for change validation when the build is broken.

### DB-layer smokes (Postgres only — no app required)

```bash
# 1. Start Postgres + apply migrations (Brain-bootstrap path or Liquibase CLI).
# 2. Run the smoke harness:
docker exec -i brain-db psql -U brain -d brain <<'SQL'
\set ON_ERROR_STOP on
INSERT INTO async_jobs (job_type, target_kind, target_id, project_id, status, started_at)
  VALUES ('SMOKE','PROJECT','smoke-target','smoke','RUNNING',now());

DO $$ BEGIN
  INSERT INTO async_jobs (job_type, target_kind, target_id, project_id, status)
    VALUES ('SMOKE','PROJECT','smoke-target','smoke','QUEUED');
  RAISE EXCEPTION 'BUG: duplicate in-flight insert succeeded';
EXCEPTION WHEN unique_violation THEN END $$;

EXPLAIN (ANALYZE) SELECT 1 FROM async_jobs
 WHERE job_type='SMOKE' AND target_kind='PROJECT' AND target_id='smoke-target'
   AND status IN ('QUEUED','RUNNING');           -- expect: Index Scan on uq_async_jobs_inflight

EXPLAIN (ANALYZE) DELETE FROM async_jobs
 WHERE status IN ('SUCCEEDED','FAILED','PARTIAL','CANCELLED')
   AND finished_at < now() - interval '90 days';  -- expect: Index Scan on idx_async_jobs_terminal_finished

DELETE FROM async_jobs WHERE target_id='smoke-target';
SQL
```

Pass criteria — every check must hold:

1. `unique_violation` raised on the duplicate in-flight insert (constraint name: `uq_async_jobs_inflight`).
2. Dedup lookup `EXPLAIN` shows `Index Scan using uq_async_jobs_inflight`.
3. Retention purge `EXPLAIN` shows `Index Scan using idx_async_jobs_terminal_finished` (Seq Scan = STRANGE-BLOCKED at scale).
4. After flipping the `RUNNING` row to `SUCCEEDED`, a fresh `QUEUED` insert for the same triple succeeds (the join-don't-reject contract — only *in-flight* rows dedup).

### App-layer smokes (full stack required — `./build-and-deploy.sh up`)

5. **Duplicate-click dedup** — fire two `POST /api/v1/projects/ingest` calls for the same `projectId` within 200 ms; both responses must carry the same `jobId` and the second must have `attachedToExisting: true`. Confirm only one row in `async_jobs` for that triple is in `(QUEUED|RUNNING)`.
6. **Cross-page snackbar** — kick off a doc bundle from `/docs`, navigate to `/projects` while it runs, wait for completion. The "Documentation ready" snackbar must fire on the Projects page; `localStorage.brain.jobs.watching` empties.
7. **OS notification** — first heavy-op kickoff in a session prompts `Notification.permission`; if granted, switching to a different Chrome tab and finishing the job fires the OS-level toast.
8. **SSE heartbeat** — open `/api/v1/jobs/stream/{jobId}` with `curl -N`; comment frame `:hb` arrives at `BRAIN_JOBS_SSE_HEARTBEAT_MS` cadence (default 15 s), keeping the connection alive past any idle proxy timeout.
9. **Concurrent emitters** — start two heavy ops simultaneously, attach SSE to each. CloudWatch / `actuator/metrics` shows `JobEventPublisher.activeJobs() == 2` and both close cleanly on terminal events (no leaked emitters).
