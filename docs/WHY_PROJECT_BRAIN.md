# Why Project Brain Exists

> **Who this document is for:** Engineering managers, product owners, directors, and anyone who needs to understand *why* this project was built and *what business problem it solves* — without reading code.

---

## The Problem

Every enterprise with multiple services faces the same pattern: **conventions, architectural decisions, and "how we do things here" knowledge is scattered** across dozens of repositories, Confluence pages, Slack threads, and the heads of senior engineers who may leave tomorrow.

When a developer picks up a new ticket, one of three things happens:

1. **They guess** — and introduce inconsistencies that someone catches in code review (wasting two people's time) or nobody catches (creating tech debt).
2. **They copy from the wrong example** — because they found a pattern in repo A, not knowing that repo B has a newer, better version.
3. **They spend hours hunting** — reading READMEs, searching Confluence, asking in Slack, waiting for a response from someone in a different timezone.

None of these outcomes are acceptable at scale.

---

## What We Tried Before (And Why It Didn't Work)

| Approach | What Goes Wrong |
|----------|----------------|
| **Copy instruction files into every repo** (CLAUDE.md, .cursorrules, CONTRIBUTING.md) | 50 repos = 50 copies. One team updates theirs, the rest drift. Within weeks you have conflicting "standards." |
| **Central wiki / Confluence page** | Developers forget to check it. AI assistants can't read it. It's always stale because nobody's sprint goal is "update the wiki." |
| **AI coding assistant rules** (Cursor rules, Copilot instructions) | Static text with no awareness of your actual code. They say "use constructor injection" but can't see that `ImeiLookupService` uses field injection with 47 dependents — blindly following the rule causes a cascade. |
| **Verbal / tribal knowledge** | Works until someone leaves, changes teams, or the company grows past 20 engineers. |

Every one of these approaches **trades a one-time writing effort for ongoing maintenance that nobody prioritizes**. The instructions rot. The code moves on. The gap widens.

---

## What Project Brain Does Instead

Project Brain replaces scattered instruction files with a **single, queryable knowledge base built from the code itself**.

### For the Developer

Instead of reading five Markdown files and hoping they're current, the developer tells the Brain:

> "Add a GSMA Device Check lost/stolen validator to the IMEI validation endpoint."

The Brain:

1. **Retrieves the actual code** — finds `IMEIValidationController.java`, `DMDNegativeCheckService`, `ImeiValidationFacade`, the real file paths, real class names.
2. **Asks clarifying questions** if it isn't sure — "Which HTTP status code for a blocked IMEI? Should the check be async?"
3. **Generates a structured plan** grounded in what the codebase actually does — with sequenced steps, affected files, applied conventions (with source proof), and ranked risks.

The developer gets a plan they can start implementing immediately. No guessing. No hunting.

### For the Engineering Manager

- **Onboarding time drops.** A new hire can ask the Brain "explain this project" and get a grounded overview in seconds, instead of spending their first week reading stale docs.
- **Consistency improves.** The Brain extracts conventions from what the code *actually does* (not what someone wrote six months ago), so plans follow real patterns.
- **Cross-project visibility.** Ingest multiple services and the Brain understands relationships across them. "How does ce-IMEI call the event manager?" is a answerable question.

### For the Organization

- **Reduced token costs.** Traditional AI assistants load all instruction files (4,000-15,000 tokens) on every request, whether relevant or not. Brain uses RAG to load only the specific context needed (2,000-6,000 tokens), cutting token spend by 50-70%.
- **Knowledge retention.** When a senior engineer leaves, their patterns are already embedded in the Brain's knowledge base — extracted from the code they wrote.
- **Audit trail.** Every convention the Brain surfaces includes its source: `[source: CONTRIBUTING.md, trust: 1.5]` or `[source: inferred from 23 occurrences, trust: 1.0]`. You can distinguish official standards from emergent habits.

---

## How It Works (Non-Technical Summary)

```
1. INGEST      You point Brain at a GitHub repo
                ↓
2. PARSE       Brain reads every Java file, build file, README, ADR
                ↓
3. EMBED       Brain converts the code into searchable vectors
                (think: a smart index that understands meaning, not just keywords)
                ↓
4. GRAPH       Brain builds a map of how classes, modules, and libraries connect
                ↓
5. QUERY       Developer asks a question or describes a requirement
                ↓
6. RETRIEVE    Brain finds the most relevant code and conventions
                (not everything — just what matters for this specific question)
                ↓
7. PLAN        Brain generates a grounded implementation plan
                with real file paths, sequenced steps, and cited conventions
```

**Re-ingestion is incremental.** When a PR merges, you re-ingest. Brain compares content hashes and only re-embeds changed files. A PR that touches 3 files out of 500 costs ~$0.0001 instead of re-embedding everything ($0.12).

---

## Cost Analysis

### Token Costs: Traditional vs. Brain

| Metric | Per-Project Instruction Files | Project Brain (RAG) |
|--------|------------------------------|---------------------|
| Tokens loaded per request | 4,000-15,000 (all instructions, always) | 2,000-6,000 (only relevant chunks) |
| Relevance of loaded tokens | 5-20% relevant to current task | 80-95% relevant |
| Daily cost (50 projects, 500 req/day) | ~$22.50/day | ~$7.50/day |
| Monthly savings | Baseline | ~$450/month saved |

### Embedding Costs (One-Time Per Ingest)

| Project Size | Files | Embedding Cost |
|-------------|-------|----------------|
| Small (50 files) | ~200 chunks | $0.002 |
| Medium (500 files) | ~2,000 chunks | $0.02 |
| Large (5,000 files) | ~20,000 chunks | $0.20 |

A large project costs $0.20 to embed. That's paid once (or on re-ingest). Compare to $22.50/day loading instruction files.

---

## What This Is Not

- **Not a code generator.** Brain generates *plans*, not code. The developer still writes the implementation.
- **Not a replacement for code review.** The plan is a starting point. It still goes through normal review processes.
- **Not magic.** If the codebase has no conventions, the Brain won't invent them. Garbage in, garbage out.
- **Not a security risk.** Code stays within your infrastructure. Embeddings are stored in your own PostgreSQL. LLM calls go to providers you choose (including local Ollama for air-gapped environments).

---

## Current Capabilities

| Capability | Status | What It Includes |
|------------|--------|------------------|
| Local Development | Available | Ollama-powered, runs on a developer laptop, full ingestion + analysis pipeline |
| AWS Deployment | Available | ECS Fargate, RDS Aurora, Bedrock (Claude + Titan), GitHub Actions CI/CD |
| Smart Intake | Available | PDF/DOCX/image OCR extraction, Jira ticket import, ad-hoc text |
| Jira Integration | Available | OAuth-based ticket proposal and creation from analyzed requirements |
| Code Generation & PR | Available | LLM-generated code with self-review, GitHub Draft PRs |
| CI Feedback & Learning | Available | Automated CI failure remediation, convention weight adjustment on merge |
| Guardrails | Available | Prompt injection prevention, PII masking, schema validation |
| Avengers Protocol | Available | 11 expert review personas as REST + MCP tools |
| Autonomous Development | Available | Multi-repo requirement intake, plan orchestration, batch PR creation |
| Future — Multi-Language | Planned | Python, TypeScript, Go AST parsing (currently Java-only for AST) |

---

## Next Steps (For Decision Makers)

1. **Try it locally.** Any developer can run the full stack in 10 minutes with zero API keys. See [Getting Started](GETTING_STARTED.md).
2. **Deploy to AWS.** Infrastructure code is committed and CI/CD pipelines are ready. The platform team needs to grant Bedrock model access and ECR permissions.
3. **Pick a pilot project.** Ingest one real service (ce-IMEI is the reference) and have the team use Brain for a sprint. Measure: time-to-first-commit for new tickets, convention violations caught in review.

---

*For technical details, see [Architecture](ARCHITECTURE.md). For deployment, see [DevOps Runbook](DEVOPS_RUNBOOK.md).*
