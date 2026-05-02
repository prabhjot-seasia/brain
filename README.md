# Project Brain

AI-powered engineering platform that ingests your codebases, understands your conventions, and autonomously generates implementation plans and pull requests across multiple repositories.

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-orange)
![React](https://img.shields.io/badge/React-18-61dafb)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-336791)
![Neo4j](https://img.shields.io/badge/Neo4j-5-008CC1)
![Redis](https://img.shields.io/badge/Redis-cache-DC382D)
![Backend Tests](https://img.shields.io/badge/backend%20tests-1238%2B-brightgreen)
![Frontend Tests](https://img.shields.io/badge/frontend%20tests-128-brightgreen)
![BDD UI](https://img.shields.io/badge/bdd%20ui%20(real%20chrome)-96%20scenarios-brightgreen)
![Coverage Gate](https://img.shields.io/badge/coverage%20gate-90%25-brightgreen)

---

## Architecture in 30 seconds

- **11 heavy operations** (ingest, doc bundle, autodev pipeline, multi-repo PR fan-out, all Avenger reviews, ticket flows, rule-pack install, CI remediation, analyze) all run through one **`AsyncJobService`** + **`/api/v1/jobs/*`** API. DB-layer in-flight de-duplication via a Postgres partial unique index — duplicate clicks ALWAYS join the existing run.
- **Server-Sent Events** push progress + terminal events to the UI. The `<JobToastWatcher>` mounted in `App.tsx` keeps the connection alive across page navigation and fires a snackbar + Web-Notification when a job ends, even if the user navigated away.
- **DRY widget library** at [`ui/src/components/widgets/`](ui/src/components/widgets/): `<ResponsiveTable>`, `<DataState>`, `<StatusChip>`, `<JobProgress>`, `<JobToastWatcher>`. Pages consume; new responsive tables / status colors / quad-state blocks are forbidden inline.
- **Avengers protocol** — 12 personas review every change. THANOS BLOCKS on missing IT, missing BDD, raw `<Table>` outside `widgets/`, etc. See [`CLAUDE.md` § Hard enforcement rules](CLAUDE.md).
- **Pre-AWS deploy gate** — 7 checks must be green before any AWS push. See [`docs/DEVOPS_RUNBOOK.md` § Pre-AWS Deploy Gate](docs/DEVOPS_RUNBOOK.md).

See [`docs/ARCHITECTURE.md` § Async Jobs + Push Notifications (UX-Q3)](docs/ARCHITECTURE.md) for the SSE flow, dedup contract, and migration status.

---

## Quick Start

```bash
ollama pull bge-m3 && ollama pull llama3.1
cp .env.example .env && cp .env.example docker/runtime.env
./build-and-deploy.sh --ollama
```

Open [http://localhost:3000](http://localhost:3000). See [Local Setup](docs/SETUP_LOCAL.md) for the full walkthrough.

---

## Admin UI Routes

| Route | Purpose |
|-------|---------|
| `/projects` | Ingested projects list |
| `/ingest` | Ingest a new repo |
| `/analyze` | Requirement intake → clarification → plan |
| `/autodev` | Autonomous multi-repo development pipeline |
| `/docs` | Generated documentation |
| `/tickets` | Jira ticket proposals + creation |
| `/prs` | Pull request status + CI remediation |
| `/learning` | Learning events / convention trust-weight history |
| `/tokens` | Per-service token usage + cost dashboard |
| `/avengers` | Avenger reviews on arbitrary code |
| `/conventions` | Project convention browser |
| `/architecture` | Mermaid topology DAG (services / queues / endpoints) |
| `/security` | HAWKEYE findings exported as ASFF v1.0 (Wave A4) |
| `/rule-packs` | Install / uninstall convention rule packs (Wave A7) |
| `/oracle` | ORACLE retrieval-budget table per project (Wave A5) |

---

## Documentation

### Getting Started

| Document | Description |
|----------|-------------|
| [Getting Started](docs/GETTING_STARTED.md) | Choose your setup path and run your first ingestion |
| [Local Setup](docs/SETUP_LOCAL.md) | Step-by-step setup with or without Docker |
| [AWS Deployment](docs/SETUP_AWS.md) | Deploy to AWS with CDK, ECS, and Bedrock |

### Understanding Brain

| Document | Description |
|----------|-------------|
| [Why Brain Exists](docs/WHY_PROJECT_BRAIN.md) | Business case and value proposition |
| [Capabilities](docs/CAPABILITIES.md) | Everything Brain can do, explained plainly |
| [Architecture](docs/ARCHITECTURE.md) | System design, data flows, and persistence |

### For Developers

| Document | Description |
|----------|-------------|
| [API Reference](docs/API_REFERENCE.md) | Every endpoint with curl examples |
| [Configuration](docs/CONFIGURATION.md) | Environment variables, profiles, and tuning |
| [Testing](docs/TESTING.md) | Test strategy, coverage gates, and writing tests |
| [Contributing](docs/CONTRIBUTING.md) | Code standards and pull request workflow |
| [MCP Integration](docs/MCP_INTEGRATION.md) | Use Brain from Claude Code or Cursor |

### For Operators

| Document | Description |
|----------|-------------|
| [DevOps Runbook](docs/DEVOPS_RUNBOOK.md) | Deployment checklist with smoke tests |
| [Autonomous Development](docs/AUTONOMOUS_DEVELOPMENT.md) | Multi-repo pipeline from requirement to pull request |

### Quality and Security

| Document | Description |
|----------|-------------|
| [Guardrails](docs/GUARDRAILS.md) | Input and output safety rails for LLM calls |
| [Avengers API](docs/AVENGERS_API.md) | Eleven quality-gate review services |
| [Avenger Memory](docs/AVENGER_MEMORY.md) | Cross-session learning from reviews |

### Other

| Document | Description |
|----------|-------------|
| [Planned Features](docs/PLANNED_FEATURES.md) | Capabilities under consideration |
| [WIP Features](docs/WIP_FEATURES.md) | Aspirational test scenarios |
