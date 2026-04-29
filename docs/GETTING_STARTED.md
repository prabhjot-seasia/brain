# Getting Started

> **Who this document is for:** Anyone setting up Project Brain for the first time. This page helps you choose the right setup path and walks you through your first ingestion and analysis.

---

## Choose Your Setup Path

Brain can run in three environments. Pick the one that fits your situation:

| I want to... | Guide |
|--------------|-------|
| Run everything locally with Docker (fastest) | [Local Setup — With Docker](SETUP_LOCAL.md#option-a-with-docker-recommended) |
| Run locally without Docker (more control) | [Local Setup — Without Docker](SETUP_LOCAL.md#option-b-without-docker) |
| Deploy to AWS for a shared environment | [AWS Deployment](SETUP_AWS.md) |

If you are unsure, start with **Local Setup — With Docker**. It requires the fewest manual steps and gets you running in under five minutes.

For a visual guide, see the [setup decision flowchart](diagrams/05-setup-decision-tree.svg).

---

## Your First Ingestion

Once Brain is running (on any setup path), ingest a repository to build the knowledge base:

1. Open the admin UI at [http://localhost:3000](http://localhost:3000)
2. Navigate to **Ingest** in the sidebar
3. Fill in:
   - **Project Name** — a short identifier (for example, `my-api`)
   - **Repository URL** — a GitHub URL (for example, `https://github.com/your-org/my-api`)
   - **Branch** — the branch to ingest (for example, `main`)
4. Click **Ingest**

Brain clones the repository, parses every source file, generates embeddings (mathematical representations that enable semantic search), and builds a knowledge graph of how classes, modules, and libraries relate to each other.

You can track progress on the **Projects** page. Ingestion typically takes 30 seconds to a few minutes depending on the repository size.

For private repositories, set `BRAIN_GITHUB_TOKEN` in your `.env` file with a GitHub personal access token that has `repo` scope. See [Configuration](CONFIGURATION.md) for details.

---

## Your First Analysis

After ingestion completes:

1. Navigate to **Analyze** in the sidebar
2. Select your ingested project from the dropdown
3. Type a requirement in plain English — for example:
   > Add rate limiting to the API gateway
4. Click **Analyze**

Brain evaluates your requirement across four dimensions: **why** (business motivation), **what** (scope), **where** (affected files), and **how** (implementation approach). If any dimension is unclear, Brain asks targeted clarification questions. Answer them and resubmit.

Once Brain is confident, it generates a structured implementation plan with:
- Affected files and confidence scores
- Ordered implementation steps
- Conventions to follow (sourced from your project's own documentation and code patterns)
- Identified risks

From here, you can proceed to code generation and pull request creation via the **Pull Requests** page, or use the **Autodev** page for the full autonomous multi-repository pipeline.

---

## Next Steps

| What you want to do | Where to go |
|---------------------|-------------|
| Understand what Brain can do | [Capabilities](CAPABILITIES.md) |
| Learn the system architecture | [Architecture](ARCHITECTURE.md) |
| Explore the API | [API Reference](API_REFERENCE.md) |
| Set up MCP for your IDE | [MCP Integration](MCP_INTEGRATION.md) |
| Configure environment variables | [Configuration](CONFIGURATION.md) |
| Deploy to AWS | [AWS Deployment](SETUP_AWS.md) |

---

*For the full list of documentation, see the [README](../README.md).*
