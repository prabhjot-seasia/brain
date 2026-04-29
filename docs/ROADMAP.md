# Roadmap

Project Brain ships in numbered Phases plus topical Waves. Phases 1–14 are foundational + autonomous-development capabilities; Waves 0–5 expand ingestion across the six dimension clusters; Wave A upgrades the Avengers; Wave R upgrades retrieval. Items marked **shipped** are present in the codebase today; items marked **planned** are tracked in [PLANNED_FEATURES.md](PLANNED_FEATURES.md) and [WIP_FEATURES.md](WIP_FEATURES.md).

## Phases (foundational)

| Phase | Theme | Status |
|------:|-------|--------|
| 1 | GitHub ingestion + clone + project detection | shipped |
| 2 | Smart intake (PDF/DOCX/image OCR + adhoc + Jira) | shipped |
| 3 | Jira ticket creation flow | shipped |
| 4 | Code generation + self-review + GitHub Draft PR | shipped |
| 5 | CI feedback loop + convention learning (trust weights) | shipped |
| 6 | Doc generation | shipped |
| 7 | Token budgets + semantic cache (Redis) | shipped |
| 8 | MCP server (16 tools) | shipped |
| 9 | Multi-repo plan generation | shipped |
| 10 | Cross-repo `DEPENDS_ON` edges + propagation | shipped |
| 11 | Convention learning loop | shipped |
| 12 | Guardrails (RailChain, 7 built-in rails) | shipped |
| 13 | Avengers REST + MCP API (11 personas) | shipped |
| 14 | Per-Avenger memory + adaptive prompt injection | shipped |
| 15 | Autonomous multi-repo development pipeline | shipped |

## Waves 0–5 — Ingestion expansion

Reorganized ingestion around six dimension clusters (A code, B runtime/ops, C data/contracts, D decisions/knowledge, E people/process, F behavior/quality). Adds 30+ pluggable `ArtifactParser` implementations covering Spring YAML/properties/XML, Liquibase, OpenAPI, AsyncAPI, GraphQL, Pact, WireMock, CDK Python, ECS YAML, GitHub Actions, ADRs, CODEOWNERS, Backstage, postmortems, runbooks, X-Ray traces, JUnit/test-runs, CloudWatch logs, pg_stat_statements, gitleaks/trufflehog, SBOM, OpenSLO, OpenFeature flags, AWS FIS, IntelliJ inspection profiles, and more. Status: **shipped**.

## Wave A — Avenger upgrades (shipped 2026-04)

| ID | Capability | Files |
|---:|-----------|-------|
| A1 | Aider SEARCH/REPLACE diff codegen for ≤3-file changes | `codegen/AiderDiffFormatter.java`, `codegen/AiderDiffApplier.java` |
| A2 | Sandboxed compile + test gate between plan nodes | `sandbox/SandboxValidationService.java` (gated by `BRAIN_SANDBOX_ENABLED`; Docker isolation P0.2 follow-up) |
| A3 | ReviewPattern → MANTIS adaptive prompt injection | `codegen/AdaptivePromptBuilder.buildReviewPatternSection` |
| A4 | HAWKEYE emits AWS Security Hub ASFF v1.0 | `avenger/hawkeye/AsffMapper.java`, `rest/v1/avengers/controller/HawkeyeAsffController.java` |
| A5 | ORACLE SLO-aware token budgeting | `avenger/oracle/OraclePromptOptimizer.java` |
| A6 | IntelliJ inspection profile parser feeds STARK | `ingest/parsers/IntellijInspectionProfileParser.java` |
| A7 | Convention rule-packs (THANOS-gated install/uninstall) | `conventions/RulePack.java`, `conventions/RulePackInstaller.java`, `rest/v1/conventions/controller/RulePackController.java` |

## Wave R — Retrieval architecture (shipped 2026-04)

| ID | Capability | Files |
|---:|-----------|-------|
| R1 | Hybrid BM25 (`pg_trgm`) + dense (pgvector) retrieval, RRF fusion | `retrieval/HybridRetrieverService.java`, Liquibase `BRAIN-017` |
| R2 | Cross-encoder reranking (text-similarity + RRF blend) | `retrieval/CrossEncoderReRanker.java` |
| R3 | GraphRAG hierarchical community summaries | `retrieval/CommunitySummarizer.java`, `graph/node/CommunitySummaryNode.java`, post-ingest hook in `IngestionService` |
| R4 | RepoCoder iterative re-query after draft plan | `retrieval/IterativeContextEnricher.java` |
| R5 | SCIP-style symbol-level cross-references | `retrieval/SymbolReferenceBuilder.java`, `graph/node/SymbolReferenceNode.java` |
| R6 | Bedrock contextual grounding rail | `guardrail/rails/ContextualGroundingRail.java`, `RailType.GROUNDING` |

The full pipeline (Hybrid → Rerank → Iterative → GraphRAG → SCIP → grounding-rail) is wired through `PlannerService` for both PLAN and EXPLAIN intents.

## UX-Q2 — Sidebar grouping + comprehensive doc PDF (shipped 2026-04-27)

| ID | Capability | Files |
|---:|-----------|-------|
| Q2.1 | Sidebar restructured into 5 grouped sections (Workspace / Develop / Deliver / Knowledge / Operations) with `<ListSubheader>` + `<Divider>` | `ui/src/components/layout/Sidebar.tsx`, `ui/src/components/layout/Sidebar.test.tsx` |
| Q2.2 | One-click comprehensive project documentation PDF (drops Type dropdown + prompt textbox); 5-section parallel generation; 60-min content-hash cache; per-project soft lock; PARTIAL → retry-only-failed; mermaid SVG via headless `mmdc`; commonmark `escapeHtml(true)` + Flying Saucer hardened XHTML parser; PDF response carries `Cache-Control: private, no-store` + CSP + `X-Content-Type-Options: nosniff` + `Referrer-Policy: no-referrer`; 50 MB size cap; in-memory rate limit (5 req / 5 min, ≥15 s apart) | `docs/FullDocBundleService.java`, `docs/FullDocBundleAggregator.java`, `docs/MermaidPreRenderer.java`, `docs/MarkdownPdfRenderer.java`, `docs/FullDocRateLimiter.java`, `rest/v1/docs/DocGeneratorController.java`, Liquibase `BRAIN-018.01/02/03`, `ui/src/pages/docs/FullDocsPage.tsx` |

`DocGeneratorService.generate` now flows through `RailChain.applyPreLlm` + `applyPostLlm` (every section call goes through input/output guardrails) and `TokenUsageTracker.track` (tracked under `DocGeneratorService` / `DOC_GENERATE`).

## Post-Wave A/R follow-ups (in priority order)

The active prioritized work plan lives in `~/.claude/plans/serialized-bubbling-clarke.md`. Summary:

- **P0** — Security blockers: Docker sandbox isolation (P0.2), continued auth hardening (P0.1 partial — RulePack header gate landed; full Spring Security RBAC pending).
- **P1** — Test coverage closure to maintain the 90% / 70% JaCoCo gate.
- **P2** — Documentation drift (this file is part of P2.6).
- **P3** — UI surfacing (ORACLE budget panel, runtime-call architecture overlay, token-usage rerank/grounding columns).
- **P4** — Reserved-schema parsers: TCMS, Selenium/Cypress/Playwright, BPMN.
- **P5** — Wave B operational features: "refuse changes to unhealthy services" guardrail, EXPLAIN_INCIDENT entry point.

## Reserved schema (no parser yet)

`TestCaseNode`, `AutomationTestNode`, `BusinessProcessNode`, `TeamProcessNode` exist in the graph schema; parsers are tracked under P4.
