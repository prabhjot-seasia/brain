# Local Setup

> **Who this document is for:** Developers setting up Project Brain on their local machine for the first time. No prior experience with the project is assumed — just basic command-line familiarity.

---

## Prerequisites

Before you begin, install the following:

| Tool | Version | How to Install |
|------|---------|----------------|
| **Java** | 21 or later | [Amazon Corretto 21](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html) or `brew install --cask corretto21` |
| **Node.js** | 18 or later | [nodejs.org](https://nodejs.org) or `brew install node` |
| **Ollama** | Latest | [ollama.com](https://ollama.com) or `brew install ollama` |

Pull the required AI models (one-time download, approximately 7 GB total):

```bash
# Embedding model — converts code into searchable vectors
ollama pull bge-m3

# Chat model — generates plans, code, and reviews
ollama pull llama3.1
```

Make sure Ollama is running before starting Brain:

```bash
ollama serve
# Or open the Ollama desktop app — it starts the server automatically
```

---

## Option A: With Docker (Recommended)

This is the fastest path. Docker manages PostgreSQL, Neo4j, and the Brain application in containers.

**Additional prerequisite:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running.

### Steps

```bash
# 1. Create environment files from the template
cp .env.example .env
cp .env.example docker/runtime.env

# 2. Build and start everything (databases + app + admin UI)
./build-and-deploy.sh --ollama

# 3. Open the admin UI
open http://localhost:3000
```

That is it. The script builds the Java application and starts PostgreSQL (with the pgvector extension for vector search), Neo4j (the knowledge graph database), Redis (the semantic cache), the Spring Boot API on port 8080, and the React admin UI on port 3000.

### Useful Commands

| Command | What It Does |
|---------|-------------|
| `./build-and-deploy.sh status` | Check container health |
| `./build-and-deploy.sh logs` | Tail application logs |
| `./build-and-deploy.sh restart` | Rebuild and restart the app (keeps databases) |
| `./build-and-deploy.sh down` | Stop everything (data is preserved) |

---

## Option B: Without Docker

Run the application directly on your machine using Gradle. You will need to install PostgreSQL, Neo4j, and Redis locally.

### Install Databases and Cache

**PostgreSQL 16 with pgvector:**

```bash
# macOS (Homebrew)
brew install postgresql@16
brew services start postgresql@16

# Create the database and enable pgvector
psql postgres -c "CREATE DATABASE brain;"
psql brain -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

**Neo4j 5 Community Edition:**

```bash
# macOS (Homebrew)
brew install neo4j
brew services start neo4j

# Set the password (default user: neo4j)
# Open http://localhost:7474 in your browser and set the password to: brain_local
```

**Redis:**

```bash
# macOS (Homebrew)
brew install redis
brew services start redis

# Verify it is running
redis-cli ping
# Should respond: PONG
```

### Configure Environment

Create your `.env` file and update the database URLs to point to localhost:

```bash
cp .env.example .env
```

Edit `.env` and change these values:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/brain
SPRING_DATASOURCE_USERNAME=brain
SPRING_DATASOURCE_PASSWORD=brain_local

SPRING_NEO4J_URI=bolt://localhost:7687
SPRING_NEO4J_AUTHENTICATION_USERNAME=neo4j
SPRING_NEO4J_AUTHENTICATION_PASSWORD=brain_local

BRAIN_REDIS_HOST=localhost
```

### Start the Application

```bash
# Terminal 1: Start the Spring Boot API
./gradlew bootRun

# Terminal 2: Start the admin UI
cd ui && npm install && npm run dev
```

The API runs on `http://localhost:8080` and the UI on `http://localhost:3000`.

---

## Cloud Configuration Toggle

Brain supports two configuration modes:

| Mode | When to Use | How to Activate |
|------|-------------|-----------------|
| **Embedded** (default) | Local development | No action needed — `application.yml` provides all defaults |
| **Cloud Config** | Shared environments (dev, staging) | Set `SPRING_PROFILES_ACTIVE=dev` and provide the config server URL |

To switch to cloud configuration:

```bash
# In your .env file
SPRING_PROFILES_ACTIVE=dev
SPRING_CLOUD_CONFIG_URI=https://your-config-server.example.com
SPRING_CLOUD_CONFIG_PASSWORD=<from your platform team>
```

When the `dev` profile is active, Brain imports database credentials, API keys, and model configuration from the Spring Cloud Config Server instead of local environment variables.

---

## Your First Ingestion

Once Brain is running, ingest a repository to build the knowledge base:

1. Open the admin UI at `http://localhost:3000`
2. Navigate to **Ingest** in the sidebar
3. Enter a project name, a GitHub repository URL, and a branch name
4. Click **Ingest**

Brain will clone the repository, parse its source code, generate embeddings, and build the knowledge graph. You can track progress on the **Projects** page.

For private repositories, set `BRAIN_GITHUB_TOKEN` in your `.env` file with a GitHub personal access token that has `repo` scope.

## Your First Analysis

After ingestion completes:

1. Navigate to **Analyze** in the sidebar
2. Select your ingested project from the dropdown
3. Type a requirement (for example: "Add rate limiting to the API")
4. Brain will either ask clarifying questions or generate an implementation plan

If Brain asks questions, answer them and submit. After enough confidence is reached, it generates a structured plan with affected files, implementation steps, conventions to follow, and risks.

---

## Troubleshooting

**Ollama connection refused:**
Ollama is not running. Start it with `ollama serve` or open the Ollama desktop app.

**Embedding model not found:**
Run `ollama pull bge-m3` to download the embedding model.

**Ollama times out on first call:**
The model loads into memory on the first request, which can take 30–60 seconds. Wait and retry, or pre-warm with `ollama run bge-m3 'hello'`.

**PostgreSQL connection refused:**
Check that PostgreSQL is running: `brew services list` (macOS) or `docker ps` (Docker).

**Neo4j connection refused:**
Check that Neo4j is running and the Bolt port (7687) is accessible. For Docker setups, verify `brain-neo4j` container is healthy with `docker ps`.

**Health endpoint shows DOWN:**
Brain reports DOWN if Redis is not running. For Docker setups, verify the `brain-redis` container is healthy with `docker ps`. For non-Docker setups, check `brew services list` and confirm Redis is started. The API functions without Redis — only semantic caching is disabled.

---

*For AWS deployment, see [AWS Deployment](SETUP_AWS.md). For all environment variables, see [Configuration](CONFIGURATION.md).*
