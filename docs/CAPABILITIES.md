# Capabilities

> **Who this document is for:** Anyone — technical or non-technical — who wants to understand what Project Brain can do. No prior programming knowledge is required to read this document.

---

## Overview

Project Brain is an intelligent engineering platform that transforms how development teams build software. It reads and understands your existing codebases, learns how your team writes code, and uses that knowledge to help plan, write, and deliver new features — across one repository or many.

The sections below describe each capability in detail.

---

## Codebase Ingestion

Project Brain connects to your GitHub repositories and reads every file: source code, configuration, documentation, and build scripts. It breaks the code into meaningful segments, converts them into mathematical representations (called embeddings), and stores them in a searchable knowledge base. It also builds a relationship graph that maps how classes, modules, and libraries connect to each other.

**Why it matters:** When Brain later generates a plan or writes code, it draws on real knowledge of your codebase — not guesses. It knows which classes exist, what conventions your team follows, and how components depend on each other.

When you update your code and re-ingest, Brain detects what changed and only re-processes the modified files, making subsequent ingestions fast.

---

## Requirement Analysis

When you provide a requirement — whether it is a Jira ticket, a pasted specification, or a free-form description — Brain does not jump straight to code. Instead, it evaluates how well it understands the requirement across four dimensions: **why** (business motivation), **what** (scope of the change), **where** (which files and modules are affected), and **how** (implementation approach).

If any dimension scores below the confidence threshold, Brain asks targeted clarification questions. This back-and-forth loop continues for up to three rounds until Brain is confident it fully understands the requirement. Only then does it generate an implementation plan.

**Why it matters:** Vague requirements produce vague code. By forcing structured clarification before planning, Brain ensures that the resulting plan is precise and actionable — not a best guess.

---

## Autonomous Multi-Repository Development

Brain can take a single requirement and determine which repositories in your organisation are affected. It proposes a list of impacted projects with confidence scores and rationale, then generates a coordinated implementation plan that spans all of them.

The autonomous pipeline has five stages, each with an explicit approval gate:

1. **Intake** — Brain accepts the requirement and identifies affected repositories.
2. **Clarification** — Brain asks questions until it fully understands the requirement.
3. **Planning** — Brain generates a per-repository implementation plan, validated against a formal schema.
4. **Execution** — Brain walks the plan graph in dependency order, generates code for each edit, and uses surgical diff-based generation where possible.
5. **Pull Request Creation** — Brain fans out draft pull requests to each affected repository. If one repository fails, the others still succeed.

**Why it matters:** Cross-repository changes are one of the hardest coordination problems in software engineering. Brain handles the dependency analysis, code generation, and pull request logistics — letting your team focus on review and approval rather than manual orchestration.

---

## Document Generation

Brain can generate architecture documentation, onboarding guides, and technical summaries by combining its knowledge of your codebase with large language model (LLM) capabilities. The output is structured Markdown, grounded in the actual code — not generic boilerplate.

**Why it matters:** Documentation that is generated from real code stays accurate. When the code changes, you can regenerate the docs to reflect the current state.

---

## Jira Ticket Decomposition

Given a high-level requirement or specification, Brain decomposes it into individual Jira tickets with titles, descriptions, acceptance criteria, story points, and priority levels. You review and edit the proposed tickets before Brain creates them in your Jira project via OAuth integration.

**Why it matters:** Breaking down large requirements into actionable tickets is time-consuming. Brain automates the decomposition while keeping the human in the loop for final approval.

---

## Code Generation and Pull Request Creation

Once Brain has an approved implementation plan, it generates production-ready code that follows your project's conventions. The generated code goes through an automated self-review loop (up to three iterations) where Brain checks for compilation errors, convention violations, and logical issues — fixing problems before a human ever sees the code.

After self-review passes, Brain pushes the code to a new branch and opens a draft pull request on GitHub with a structured summary of what was changed and why.

**Why it matters:** The self-review loop catches the kinds of mistakes that waste reviewer time. Draft pull requests signal that the code is ready for human review, not that it should be merged blindly.

---

## Continuous Integration Feedback Loop

When a GitHub Actions workflow runs on a Brain-generated pull request and fails, Brain automatically parses the failure logs, identifies the root cause, and pushes a fix. It retries up to a configurable number of times before stopping.

When a pull request is merged, Brain analyses which conventions held up during review and which were overridden by the developer. It adjusts the trust weight of each convention accordingly — strengthening rules that consistently survive review and weakening ones that developers regularly override.

**Why it matters:** Brain learns from every merge. Over time, its plans and generated code align more closely with how your team actually works, not just how the documentation says you should work.

---

## Convention Learning

Brain extracts coding conventions from your repositories — both from explicit documentation (such as CONTRIBUTING.md files) and from patterns observed in the code itself. Each convention carries a trust weight that increases when the convention is followed and decreases when it is overridden.

Frequently violated conventions are automatically strengthened in future prompts with explicit "do not" examples, while consistently followed conventions are reinforced with positive patterns.

**Why it matters:** Conventions that exist only in documentation are routinely ignored. Brain's adaptive learning means that conventions are enforced based on what your team actually does, not just what a wiki page says.

---

## Quality Gates (The Avengers)

Every change Brain produces is reviewed by eleven specialised quality-gate personas, collectively called the Avengers. Each persona owns a specific domain:

| Persona | Domain |
|---------|--------|
| THANOS | Overall quality — reviews all other personas' work and catches gaps |
| STARK | Code quality — architecture, design principles, conventions |
| HAWKEYE | Security — vulnerability scanning, secret detection, input validation |
| WIDOW | Testing — coverage, test quality, missing test scenarios |
| FURY | Documentation — accuracy, clarity, completeness |
| VISION | User interface — responsiveness, accessibility, design compliance |
| ORACLE | Token economy — cost tracking, caching, prompt optimisation |
| FORGE | Code validation — syntax checking, convention enforcement |
| MANTIS | Intelligence — pattern learning, adaptive prompt engineering |
| HULK | Performance — response times, resource usage, query efficiency |
| JARVIS | Infrastructure — deployment, containerisation, CI/CD pipelines |

These personas are also available as callable services through the REST API and through MCP tools for IDE integration.

**Why it matters:** Automated quality gates catch issues that manual review misses. Having eleven specialised reviewers — each with deep domain expertise — provides coverage that no single reviewer can match.

---

## Token Cost Management

Every call Brain makes to a large language model is tracked: input tokens, output tokens, latency, and estimated cost. A semantic cache stores responses to similar prompts, so repeated or near-identical requests are served from cache without consuming additional tokens.

A dashboard provides real-time visibility into token usage, cache hit rates, and per-service cost breakdowns.

**Why it matters:** LLM costs can escalate quickly. Brain's caching and tracking ensure you know exactly what you are spending and that redundant work is eliminated.

---

## Input and Output Guardrails

Every interaction with a large language model passes through a configurable chain of safety rails:

- **Prompt injection detection** prevents malicious inputs from manipulating the model's behaviour.
- **PII masking** automatically redacts personally identifiable information before it reaches the model.
- **Input and output length limits** prevent resource exhaustion.
- **Schema validation** ensures that model responses conform to expected formats before they are acted upon.

**Why it matters:** Safety rails protect against both accidental and deliberate misuse. They operate transparently — you do not need to think about them, but they are always active.

---

*For technical details on any capability, see the [Architecture](ARCHITECTURE.md) and [API Reference](API_REFERENCE.md) documents.*
