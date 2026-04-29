# MCP Integration (Claude Code / IDE)

> **Who this document is for:** Developers who want to use Project Brain directly from their IDE (Claude Code, Cursor, or any MCP-compatible tool) without opening a browser. You should be comfortable editing JSON config files.

---

## What This Gives You

With MCP integration, you can do this directly in your IDE:

```
You: Use the project brain to analyze this requirement for ce-imei:
     "Add a GSMA Device Check lost/stolen validator"

Claude: [calls analyze_requirement tool]
        The Brain needs clarification on 2 points:
        1. Should the GSMA check be sync or async?
        2. What HTTP status for blocked IMEIs?
```

No browser. No curl. The Brain's analysis, conventions, and project info are available as IDE tools.

---

## Prerequisites

1. **Brain API is running** (locally or deployed). See [Getting Started](GETTING_STARTED.md).
2. **Node.js 18+** is installed.
3. **You have at least one project ingested** in the Brain.

---

## Setup

### Step 1 — Build the MCP Server

```bash
cd mcp
npm install
npm run build
# This creates mcp/dist/server.js
```

### Step 2 — Configure Your IDE

#### Claude Code

Add to `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "project-brain": {
      "command": "node",
      "args": ["/absolute/path/to/project-brain/mcp/dist/server.js"],
      "env": {
        "BRAIN_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

Replace `/absolute/path/to/project-brain` with the actual path on your machine.

Then restart Claude Code (close and reopen, or run `/mcp` to reload).

#### Cursor

Add to `.cursor/mcp.json` in your project root:

```json
{
  "mcpServers": {
    "project-brain": {
      "command": "node",
      "args": ["/absolute/path/to/project-brain/mcp/dist/server.js"],
      "env": {
        "BRAIN_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Step 3 — Verify

In Claude Code, type:

```
Use the project brain to list all projects
```

You should see a list of your ingested projects. If you see an error, check that the Brain API is running and the URL in `env` is correct.

---

## Available Tools

> **Wave A endpoints are HTTP-only (not yet wrapped as MCP tools):** the HAWKEYE ASFF export (`GET /api/v1/avengers/hawkeye/findings.asff`), the ORACLE budget endpoint (`GET /api/v1/avengers/oracle/budget/{projectId}`), the Convention Rule Pack install/uninstall calls, and the **UX-Q2.2 Comprehensive Doc PDF** endpoints (`POST /api/v1/docs/full/{projectId}`, `GET /docs/full/status/{id}`, `POST /docs/{id}/retry-failed`, `GET /docs/{id}/pdf`, `GET /docs/full?projectId=`) require an HTTP client and are not surfaced through the MCP server in v1. Use `curl` or the Admin UI (`/docs`, `/security`, `/oracle`, `/rule-packs` routes) until they are added to `mcp/src/server.ts`.

### list_projects

Lists all ingested projects with their metadata.

**Example prompt:** "Show me all projects in the Brain"

**Returns:** Array of projects with id, name, language, framework, buildTool, lastIngested.

### get_project

Gets details of a specific project.

**Example prompt:** "Get details for the ce-imei project from the Brain"

**Input:** `projectId` (string)

### analyze_requirement

Starts or continues a requirement analysis session.

**Example prompt:** "Use the Brain to analyze this requirement for ce-imei: Add rate limiting to the validation endpoint"

**Input:** `projectId`, `requirement`, optional `sessionId` and `answers`

**Returns:** Either clarification questions (with dimension scores) or a structured implementation plan.

### get_conventions

Lists conventions extracted from a project's codebase.

**Example prompt:** "What are the coding conventions for ce-imei?"

**Input:** `projectId`, optional `category` filter

---

## Avenger Review Tools

Each of the 11 Avengers is exposed as an MCP tool that calls `POST /api/v1/avengers/{name}/review`. Every review routes through the guardrail chain and is persisted in `avenger_reviews`. See [Avengers API](AVENGERS_API.md) for backend details.

| Tool | Domain | Example Prompt |
|------|--------|----------------|
| `review_with_stark` | Code correctness (SOLID, DRY, zero hardcoding). Uses AST validator — zero LLM tokens. | "Use STARK to review PaymentService.java for ce-imei" |
| `review_with_hawkeye` | Security — OWASP Top 10, secrets, prompt injection, PII | "Have HAWKEYE security-review the new auth handler" |
| `review_with_vision` | UI/UX — responsive design, MUI compliance, accessibility | "Ask VISION to review the new dashboard page" |
| `review_with_widow` | Test quality — coverage gaps, bogus tests, BDD | "Get WIDOW to audit the PaymentControllerTest" |
| `review_with_hulk` | Performance — response budgets, memory, thread pools, queries | "Have HULK performance-review this service method" |
| `review_with_fury` | Documentation — accuracy, audience-fit, code-sync | "Get FURY to check if this doc is still accurate" |
| `review_with_forge` | Code validation — AST, conventions, hallucinations, diffs | "Ask FORGE to validate the generated code" |
| `review_with_oracle` | Token economy — budgets, caching, prompt optimization | "Have ORACLE audit token usage for this service" |
| `review_with_mantis` | Learning — pattern reuse, convention weights, adaptive prompts | "Get MANTIS to review the learning system health" |
| `review_with_jarvis` | Infrastructure — CDK, Docker, CI/CD, AWS security | "Ask JARVIS to review the deploy pipeline" |
| `review_with_thanos` | Overseer — cross-domain gaps, SoC enforcement | "Run THANOS to catch anything the others missed" |

All 11 tools accept the same input: `{ projectId, code, context? }`.

### run_full_avengers_review

Runs all 11 Avengers in parallel and returns an aggregated verdict plus individual verdicts.

**Example prompt:** "Run the full Avengers review on this PR for ce-imei"

**Input:** `{ projectId, code, context? }`

**Returns:** `overallVerdict` (APPROVED / CHANGES_REQUESTED / BLOCKED) plus 11 individual Avenger responses. Overall is BLOCKED if any blocks, else CHANGES_REQUESTED if any request changes, else APPROVED.

---

## Pointing to a Deployed Brain

To use the Brain running on AWS instead of localhost:

```json
{
  "env": {
    "BRAIN_API_URL": "https://dev.hyla.hylatest.com/project-brain-backend"
  }
}
```

---

## Troubleshooting

### "Tool not found" / MCP server not loading

1. Check the path to `server.js` is absolute and correct
2. Run `node /path/to/mcp/dist/server.js` manually — if it crashes, you'll see the error
3. Make sure you ran `npm run build` in the `mcp/` directory

### "Connection refused" / "Network error"

The Brain API isn't running. Start it with `./build-and-deploy.sh --ollama`.

### "Project not found"

You haven't ingested any projects yet. See [Getting Started](GETTING_STARTED.md#your-first-ingestion).

---

## Development

If you're modifying the MCP server:

```bash
cd mcp

# Development mode (auto-reloads on file changes)
npm run dev

# Production mode
npm start
```

The MCP server is a thin HTTP client — it translates MCP tool calls into Brain API requests. The source is in `mcp/src/server.ts`.

---

*For API details, see [API Reference](API_REFERENCE.md). For setup, see [Getting Started](GETTING_STARTED.md).*
