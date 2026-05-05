# Deploying Project Brain to AWS

Production deployment guide for Project Brain on AWS using:
- **ECS Fargate** for the Spring Boot app
- **RDS Aurora PostgreSQL 16** with `pgvector`
- **Neo4j 5 Community** on a single EC2 instance with EBS
- **AWS Bedrock** for both LLM (Claude Sonnet 4.5) and embeddings (Titan Text v2, 1024 dims)
- **Application Load Balancer** with HTTPS
- **Secrets Manager** for DB and Neo4j credentials
- **ECR** for the container image
- **GitHub Actions + OIDC** for CI/CD

The CDK stacks are committed at [`.infra/`](../.infra/) and follow the same Python CDK patterns as `gl-dls-ce-eventmanager` (bring-your-own VPC, shared route tables, OIDC GitHub Actions auth). This guide tells an operator how to take what's in the repo and deploy it.

> **Who this document is for:** DevOps engineers and developers deploying Project Brain to AWS. Assumes familiarity with AWS console, CDK, and GitHub Actions. This is separate from the local quickstart in [`README.md`](../README.md).

---

## 0. What you need before you start

### AWS-side prerequisites

| Item | How to get it |
|---|---|
| AWS account with admin or scoped permissions | Talk to your platform team |
| Required IAM permissions | ECS, ECR, RDS, EC2, IAM, Secrets Manager, CloudWatch Logs, ALB, Bedrock (`InvokeModel`, model-access), VPC read |
| **Bedrock model access enabled** in your target region for `amazon.titan-embed-text-v2:0`, `anthropic.claude-sonnet-4-5-v1:0`, and `anthropic.claude-haiku-4-5-v1:0` | Bedrock console → Model access → Edit → check the boxes → Save |
| A VPC with at least 2 private subnets in 2 different AZs | Bring-your-own per Assurant convention. The CDK creates new subnet objects in your existing VPC and attaches them to shared route tables — see [`network_stack.py`](../.infra/stacks/network_stack.py). |
| ACM certificate ARN for the ALB hostname | ACM console → Request certificate (DNS validation is fastest) |
| GitHub OIDC trust set up between your repo and the AWS account | Standard `aws-actions/configure-aws-credentials` setup. Same role is used by `publish.yml` and `deploy.yml`. |

### Local prerequisites

```bash
brew install awscli
brew install --cask aws-cdk         # cdk v2
brew install python@3.11
brew install --cask docker          # for the first manual image push
brew install --cask temurin@21      # for the bootJar build
```

```bash
cd .infra
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

---

## 0a. Where every secret comes from

Project Brain reads its config from `gl-dls-ce-config-files/project-brain.yml` (Spring Cloud Config). Every `${VAR}` placeholder in that file is one of: an AWS Secrets Manager value, a console-issued credential, or a third-party PAT. Provision them in this order before the first deploy. The `target` column is the Spring Cloud Config env-var name the ECS task expects; the `source` column is exactly where to acquire it from.

| Env var | Source | Acquire it like this |
|---|---|---|
| `SPRING_DATASOURCE_PASSWORD` | AWS Secrets Manager — `dev-brain/rds/master` (auto-created by `CDK-DEV-BRAIN-DATA` for env-managed RDS, or by your platform team for shared RDS) | `aws secretsmanager get-secret-value --secret-id dev-brain/rds/master --query SecretString --output text \| jq -r .password`. Wire to the ECS task as a `secret:` reference in `ecs_stack.py`, never a plaintext env var. |
| `SPRING_NEO4J_PASSWORD` | AWS Secrets Manager — `dev-brain/neo4j/master` (auto-created by `CDK-DEV-BRAIN-NEO4J` user-data on first boot) | `aws secretsmanager get-secret-value --secret-id dev-brain/neo4j/master --query SecretString --output text \| jq -r .password`. The Neo4j stack writes this in `init.sh`; do not rotate manually without also restarting the ECS task. |
| `BRAIN_JWT_SECRET` | AWS Secrets Manager — `dev-brain/app/jwt-secret` (create once per env) | `openssl rand -base64 48 \| tr -d '\n'` then `aws secretsmanager create-secret --name dev-brain/app/jwt-secret --secret-string <value>`. Must be ≥ 32 chars or `JwtTokenProvider` refuses to start. |
| `BRAIN_GITHUB_TOKEN` | GitHub PAT (classic) | github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token. Scopes: `repo` (read + write), `workflow` (so PR-creation flows can update workflow files when needed). Store as `dev-brain/app/github-pat` in Secrets Manager. |
| `BRAIN_GITHUB_WEBHOOK_SECRET` | GitHub repo settings — Webhooks → Add webhook | `openssl rand -hex 32` → paste into the GitHub webhook config AND store the same value in Secrets Manager as `dev-brain/app/github-webhook-secret`. The webhook posts to `https://${ALB}/project-brain-backend/api/v1/webhooks/github`. HMAC-verified; without this set, webhooks are rejected outright. |
| `BRAIN_JIRA_BASE_URL` | Your Atlassian Cloud site URL | e.g. `https://yourorg.atlassian.net`. Plain config value, not a secret — but keep it in Secrets Manager (`dev-brain/app/jira-config`) alongside the rest of the Jira block for symmetry. |
| `BRAIN_JIRA_EMAIL` | Atlassian account that owns the API token | The email of a service-account Atlassian user (preferred) or the operator. This account's permissions in Jira determine what Project Brain can read/create. |
| `BRAIN_JIRA_API_TOKEN` | Atlassian Cloud API token | id.atlassian.com → Manage profile → Security → API tokens → Create API token. Copy once — Atlassian will not show it again. Store as `dev-brain/app/jira-api-token` in Secrets Manager. |
| `BRAIN_JIRA_WEBHOOK_SECRET` | Self-issued | `openssl rand -hex 32` → store in Secrets Manager (`dev-brain/app/jira-webhook-secret`) AND configure on the Atlassian webhook (Settings → System → WebHooks → set as `?token=` query string or `X-Brain-Webhook-Token` header). Webhook deliveries are constant-time compared against this; without it, deliveries are rejected. |
| `BRAIN_CONFLUENCE_API_TOKEN` | Atlassian Cloud API token (same flow as Jira) | Same id.atlassian.com → API tokens. The same token works for Confluence and Jira if the account has access to both — but issuing separate tokens per integration is the cleaner audit trail. Store as `dev-brain/app/confluence-api-token`. |
| `BRAIN_RULEPACK_APPROVAL_TOKEN` | Self-issued | `openssl rand -hex 32` → store in Secrets Manager (`dev-brain/app/rulepack-approval-token`). Required by `RulePackController` to gate convention-rulepack updates. Leave unset to disable rulepack approvals entirely. |
| `BRAIN_HAWKEYE_AWS_ACCOUNT_ID` | Your AWS account ID | `aws sts get-caller-identity --query Account --output text`. Plain config, not a secret — but pin it explicitly so HAWKEYE's emitted ASFF findings carry the right `AwsAccountId`. |
| `BRAIN_SANDBOX_DOCKER_IMAGE` | Your ECR repo | The full image URI of the sandbox runtime, e.g. `${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/project-brain-sandbox:latest`. Build via `docker/sandbox/Dockerfile`, push alongside the main app image. Leave unset to disable Docker-isolated sandbox validation (falls back to host-process validation). |

> **Rotation:** for every secret in Secrets Manager, set a rotation cadence (90 days for app secrets, follow your platform team's policy for RDS/Neo4j). After rotation, run `aws ecs update-service --force-new-deployment` so the task picks up the new value — Spring does not poll Secrets Manager.

> **What about CDK-managed secrets?** `CDK-DEV-BRAIN-NEO4J` creates the Neo4j password automatically on stack deploy; `CDK-DEV-BRAIN-ECS` reads it via `secretsmanager.Secret.from_secret_name_v2(...)` and wires it to the task as a `secret:` (not `environment:`). The same pattern applies to anything you create yourself — write it to Secrets Manager, then reference it in the ECS task definition by ARN, never by plaintext.

---

## 1. Bootstrap CDK (one time per account/region)

```bash
export AWS_PROFILE=<your-profile>
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export AWS_REGION=us-east-1
cdk bootstrap aws://${AWS_ACCOUNT_ID}/${AWS_REGION}
```

This provisions the CDK toolkit stack (S3 bucket for assets, ECR repo for CDK assets, IAM roles).

---

## 2. Configure the environment

Edit [`.infra/config.yaml`](../.infra/config.yaml) and fill in the placeholders for the environment you want to deploy. The included file already contains a `default` block with the Bedrock model IDs and an `embed` block pinned to `bedrock-titan` / 1024 dims — those don't need to change.

What **you** must supply for `dev`:

```yaml
environments:
  dev:
    vpc:
      id: 'vpc-0123456789abcdef0'                                          # YOUR vpc id
      subnets:
        - cidr: '10.11.53.0/25'
          route_table: 'rtb-0123...'                                       # shared route table id
        - cidr: '10.11.53.128/25'
          route_table: 'rtb-0456...'
    api:
      certificate: 'arn:aws:acm:us-east-1:572232158477:certificate/...'    # ACM cert ARN
      domain:      'brain.dls-test.com'
      subdomain:   'dev-brain'
```

For `prod`, do the same plus override sizes (`rds.instance_class: db.r6g.xlarge`, `multi_az: true`, `ecs.desired_count: 2`, etc.).

> **About the subnet pattern:** like `gl-dls-ce-eventmanager`, project-brain creates `ec2.PrivateSubnet` constructs in your existing VPC and overrides their default route table to a shared one supplied by config. This is the Assurant networking convention — don't change it without coordinating with the platform team.

---

## 3. Provision the ECR repo

The ECR repo is account-scoped (one repo for all environments). It must exist before the first image push.

```bash
cd .infra
source .venv/bin/activate
cdk deploy CDK-BRAIN-ECR
```

---

## 4. Build and push the first image manually

Subsequent deploys go through GitHub Actions (`publish.yml`), but you need the first image in ECR before `cdk deploy CDK-DEV-BRAIN-ECS` can succeed.

```bash
# From the project root
./gradlew bootJar

aws ecr get-login-password --region ${AWS_REGION} | \
  docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

IMAGE_URI=${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/project-brain
docker build -t ${IMAGE_URI}:0.1.0 -t ${IMAGE_URI}:latest -f Dockerfile .
docker push ${IMAGE_URI}:0.1.0
docker push ${IMAGE_URI}:latest
```

---

## 5. Deploy the per-environment stacks

The stacks have dependencies — deploy them in this order. The CDK will refuse to deploy out of order anyway.

```bash
cd .infra
source .venv/bin/activate

# 1. Network: VPC lookup, security groups, subnets in shared route tables
cdk deploy CDK-DEV-BRAIN-NETWORK

# 2. Neo4j: EC2 + EBS + user-data installer + Secrets Manager Neo4j credentials (~5 min)
cdk deploy CDK-DEV-BRAIN-NEO4J

# 3. ECS: Fargate cluster, task def with Bedrock IAM, service, ALB
cdk deploy CDK-DEV-BRAIN-ECS -c image_tag=0.1.0
```

**About the `image_tag` context value:** the ECS task definition pins the container image at synth time. To roll out a new image after this initial deploy, use the `deploy.yml` GitHub Actions workflow (which calls `aws ecs update-service --force-new-deployment`) instead of re-running `cdk deploy`.

---

## 6. Smoke test

```bash
# Find the ALB DNS name
ALB_DNS=$(aws elbv2 describe-load-balancers \
  --region ${AWS_REGION} \
  --query "LoadBalancers[?LoadBalancerName=='dev-brain-alb'].DNSName" \
  --output text)

curl -s "https://${ALB_DNS}/project-brain-backend/actuator/health" | jq
# Expect:
# {
#   "status": "UP",
#   "components": {
#     "embedding": {
#       "status": "UP",
#       "details": { "dimensions": 1024, "model": "BedrockTitanEmbeddingModel" }
#     },
#     ...
#   }
# }
```

If `embedding` is not `UP`, check:
1. Bedrock model access is enabled in the console for `amazon.titan-embed-text-v2:0`.
2. The ECS task role has `bedrock:InvokeModel` on the foundation-model ARN — see [`ecs_stack.py`](../.infra/stacks/ecs_stack.py) (the `bedrock-invoke` inline policy).
3. CloudWatch Logs at `/ecs/project-brain-dev` for the actual Bedrock SDK error.

Then ingest a small project end-to-end. Ingest returns **202 + jobId** immediately; subscribe to the SSE stream (or poll the job) to track progress.

```bash
# 1. Kick off ingestion — returns 202 with jobId, projectId, streamUrl, pollUrl
RESP=$(curl -sS -X POST "https://${ALB_DNS}/project-brain-backend/api/v1/projects/ingest" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${BRAIN_JWT}" \
  -d '{"projectId":"test-project","projectName":"Test","repoUrl":"https://github.com/assurant/gl-dls-ce-imei.git","branch":"master"}')
echo "$RESP" | jq
JOB_ID=$(echo "$RESP" | jq -r .jobId)

# 2. Subscribe to the SSE stream — receives snapshot, progress[], terminal event (succeeded / failed / partial)
curl -N -H "Authorization: Bearer ${BRAIN_JWT}" \
  "https://${ALB_DNS}/project-brain-backend/api/v1/jobs/stream/${JOB_ID}"

# 2b. Or poll the job for MCP / curl-only clients
while true; do
  S=$(curl -sS -H "Authorization: Bearer ${BRAIN_JWT}" \
        "https://${ALB_DNS}/project-brain-backend/api/v1/jobs/${JOB_ID}" | jq -r .status)
  echo "$S"; [[ "$S" == "SUCCEEDED" || "$S" == "FAILED" || "$S" == "PARTIAL" ]] && break
  sleep 5
done

# 3. Smoke the dedup contract — second click within the in-flight window must JOIN, not duplicate
curl -sS -X POST "https://${ALB_DNS}/project-brain-backend/api/v1/projects/ingest" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${BRAIN_JWT}" \
  -d '{"projectId":"test-project","projectName":"Test","repoUrl":"https://github.com/assurant/gl-dls-ce-imei.git","branch":"master"}' | jq
# Expect: same jobId, "attachedToExisting": true (as long as the first ingest is still QUEUED|RUNNING)
```

If the SSE stream returns `event:failed` or the polled job ends in `FAILED`, the `errorMessage` field in the row carries the cause (clone auth, embedding model access, etc.). Use the operational tools in § 8 to drill in.

---

## 7. Ongoing deploys via GitHub Actions

Add these GitHub repo secrets first:

| Secret | Value |
|---|---|
| `DLS_CE_AWS_ACCOUNT` | Your AWS account ID |
| `DLS_CE_AWS_REGION` | Your AWS region (e.g. `us-east-1`) |
| `DLS_CE_GHA_DEPLOY_ROLE` | Name of the IAM role GitHub OIDC will assume (configure trust to your repo) |

Then the flow is:

1. **Tag a release locally**:
   ```bash
   git tag v0.1.1 && git push --tags
   ```

2. **Run the publish workflow** (manually from the GitHub UI):
   - Actions → "Publish image to ECR" → Run workflow → version `0.1.1`
   - This builds the JAR, builds a Docker image, pushes it to ECR as `project-brain:0.1.1` AND `project-brain:latest`.

3. **Run the deploy workflow**:
   - Actions → "Deploy to ECS" → Run workflow → environment `dev`, image_tag `0.1.1`
   - This calls `aws ecs update-service --force-new-deployment`, waits for stable, and curls `/project-brain-backend/actuator/health`.

The workflows live at [`.github/workflows/publish.yml`](../.github/workflows/publish.yml) and [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml). Both follow the same OIDC pattern as `gl-dls-ce-eventmanager`.

---

## 8. Operational notes

### CloudWatch Logs

```bash
aws logs tail /ecs/project-brain-dev --follow
```

### Database access from a bastion

The RDS cluster is in a private subnet. Use Session Manager + a tiny bastion or `aws ssm start-session` against any instance in the same VPC:

```bash
SECRET_ARN=$(aws secretsmanager describe-secret --secret-id dev-brain/rds/master --query ARN --output text)
DB_PASSWORD=$(aws secretsmanager get-secret-value --secret-id "$SECRET_ARN" --query SecretString --output text | jq -r .password)
DB_HOST=$(aws rds describe-db-clusters --db-cluster-identifier dev-brain-pgvector --query 'DBClusters[0].Endpoint' --output text)

PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U brain -d brain -c "SELECT count(*) FROM chunks;"
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U brain -d brain -c "SELECT vector_dims(embedding) FROM chunks LIMIT 1;"
# → 1024
```

### Neo4j access

The Neo4j EC2 instance is also private. Same SSM + cypher-shell trick, or expose it via a one-off security group rule for debugging.

```bash
NEO4J_PWD=$(aws secretsmanager get-secret-value --secret-id dev-brain/neo4j/master --query SecretString --output text | jq -r .password)
echo "neo4j password: $NEO4J_PWD"
# Then connect via Bolt with cypher-shell
```

### Cost estimate (dev environment, idle baseline)

| Resource | Spec | Approx. monthly |
|---|---|---|
| RDS Aurora db.t4g.medium (single-AZ) | 1 instance, 50 GB storage | ~$45 |
| Neo4j EC2 t3.medium + 100 GB EBS gp3 | | ~$30 + ~$8 |
| ECS Fargate 1 vCPU / 2 GB | 1 task | ~$36 |
| Application Load Balancer | | ~$22 |
| CloudWatch Logs | 30-day retention | ~$5 |
| Bedrock | Pay-per-token | $0 idle |
| **Idle baseline** | | **~$146 / month** |

Plus per-token Bedrock costs when ingesting / analyzing:
- Embeddings (Titan v2): ~$0.02 / 1M tokens
- Plan generation (Sonnet 4.5): ~$3 input + $15 output / 1M tokens
- Clarifier (Haiku 4.5): ~$0.80 input + $4 output / 1M tokens

Production sizing roughly doubles the baseline (2 ECS tasks across 2 AZs, db.r6g.xlarge multi-AZ Aurora, larger Neo4j).

### Async-jobs operations (UX-Q3)

The unified async-job pipeline is the surface for every heavy operation. To debug a stuck or failing job in AWS:

```bash
# 1. List recent jobs for a project (uses idx_async_jobs_project_created)
curl -sS -H "Authorization: Bearer $BRAIN_JWT" \
  "https://${ALB_DNS}/project-brain-backend/api/v1/jobs?projectId=ce-imei&limit=20" | jq

# 2. Inspect a specific job snapshot
curl -sS -H "Authorization: Bearer $BRAIN_JWT" \
  "https://${ALB_DNS}/project-brain-backend/api/v1/jobs/${JOB_ID}" | jq

# 3. From a bastion / Session Manager — confirm the partial unique index is doing its job
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U brain -d brain <<'SQL'
\d+ async_jobs
EXPLAIN ANALYZE SELECT 1 FROM async_jobs
 WHERE job_type='INGEST_PROJECT' AND target_kind='PROJECT' AND target_id='ce-imei'
   AND status IN ('QUEUED','RUNNING');
-- expect: Index Scan using uq_async_jobs_inflight
SQL
```

**Tunables (cloud config — `gl-dls-ce-config-files/project-brain.yml`):**
- `brain.jobs.sse-heartbeat-ms` — comment-frame interval, default 15000. Increase if ALB idle timeout is longer; decrease if proxies are aggressive.
- `brain.jobs.retention-days` — terminal-row purge cutoff, default 90. The retention task runs nightly per `brain.jobs.retention-cron`.
- `brain.security.enforce-project-membership` — **must be `true` in prod**. `false` is the rollout-only soft mode (logs would-deny and allows).

**Operational metrics surfaced via `/actuator/metrics`:** `JobEventPublisher.activeJobs()`, `JobEventPublisher.activeSubscribers(jobId)`. SSE leaks (emitters never closed) show up as `activeJobs` not decrementing after a deploy — the heartbeat scheduler evicts dead emitters automatically, but a positive count over hours warrants a thread dump.

### Bedrock model access changes

If you ever swap models, update **both**:
1. `.infra/config.yaml` → `bedrock.embedding_model` / `plan_model` / `extract_model`
2. The IAM grant in [`ecs_stack.py`](../.infra/stacks/ecs_stack.py) — the `bedrock-invoke` inline policy lists exact foundation-model ARNs.

Then `cdk deploy CDK-DEV-BRAIN-ECS` to update the task role.

---

## 9. Teardown

```bash
cd .infra
source .venv/bin/activate

cdk destroy CDK-DEV-BRAIN-ECS
cdk destroy CDK-DEV-BRAIN-NEO4J
# No CDK-DEV-BRAIN-DATA — RDS is external
cdk destroy CDK-DEV-BRAIN-NETWORK
# Repeat for prod, then:
cdk destroy CDK-BRAIN-ECR
```

> **Heads up:** EBS volumes on the Neo4j instance default to `delete_on_termination=false` for prod — clean them up manually after destroying the stack.

---

## 10. Migrating from Local to AWS

If you're moving from the local Ollama setup to AWS:

| Local | AWS | Migration step |
|---|---|---|
| Ollama `bge-m3`, 1024 dims | Bedrock Titan v2, 1024 dims | **None** — same dimension, same Liquibase schema. |
| pgvector on a Docker container | RDS Aurora pgvector | Liquibase migrations run automatically on first ECS task boot. |
| Neo4j on a Docker container | Neo4j on EC2 | Re-ingest projects (graph isn't migrated, just rebuilt during ingestion). |
| `BRAIN_LLM_PROVIDER=ollama` | `BRAIN_LLM_PROVIDER=bedrock` | `application-dev.yml` overrides this — no manual change needed. |
| `BRAIN_EMBED_PROVIDER=ollama` | `BRAIN_EMBED_PROVIDER=bedrock-titan` | `application-dev.yml` handles it. |
| No API key | IAM task role | Provisioned by CDK — no API key, no static credentials. |

The whole point of pinning both Ollama bge-m3 and Bedrock Titan v2 to **1024 dims** is that the schema is identical between environments. You don't run a second migration when you go to AWS.
