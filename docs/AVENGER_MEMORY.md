# Avenger Memory

> **Who this document is for:** Developers extending the learning system, or debugging why an Avenger's prompt gains reinforcement hints after repeated violations. Written for someone familiar with Project Brain's learning system and the Avengers protocol.

Each Avenger remembers patterns it has observed across sessions on a per-project basis. After seeing the same violation multiple times, an Avenger's persona prompt is automatically reinforced with that pattern for future reviews — no manual intervention required.

---

## Research Foundation

From [Analytics Vidhya 2026-04 on memory systems](https://www.analyticsvidhya.com/blog/2026/04/memory-systems-in-ai-agents/), intelligent agents need **two memory layers**:

- **Episodic** — raw observation events
- **Semantic** — patterns distilled from repeated episodes ("this codebase frequently violates X")

The semantic layer is an on-demand projection over episodic events. We do NOT persist semantic memory separately — it is computed from episodic events on cache miss and stored in Redis with a 24h TTL.

---

## Data Model

**Episodic (existing + extended):** Every Avenger review that returns `CHANGES_REQUESTED` or `BLOCKED` emits one `LearningEvent` row per issue:

| Field | Value |
|-------|-------|
| `event_type` | `AVENGER_VIOLATION_OBSERVED` |
| `avenger_id` | The observing Avenger (HAWKEYE, STARK, …) |
| `project_id` | The project being reviewed |
| `convention_rule` | The issue text returned by the Avenger |
| `created_at` | Event time |

Nullable `avenger_id` column + composite index `(avenger_id, project_id, created_at DESC)`.

**Semantic (new, Redis):** `AvengerMemorySnapshot` computed on-demand, cached for 24h:

```json
{
  "avenger": "HAWKEYE",
  "projectId": "ce-imei",
  "totalEvents": 12,
  "violationCounts": {"no-field-injection": 5, "pii-leak": 2},
  "topViolations": ["no-field-injection", "pii-leak"],
  "recentIssues": ["Field injection (@Autowired) detected on field X", "..."],
  "trendHint": "HAWKEYE has seen 'no-field-injection' violated 5 times on this project — be strict."
}
```

Redis key: `brain:avenger:{NAME}:memory:{projectId}`

---

## Architecture

```
Avenger review finishes
        ↓
if verdict != APPROVED:
  emit N LearningEvent rows (one per issue)  [episodic]
  invalidate Redis cache                       [force rebuild next call]
        ↓
Next review for same Avenger + project:
        ↓
AdaptivePromptBuilder.buildAdaptiveSection(projectId, avenger)
        ↓
AvengerMemory.getMemory(avenger, projectId)
├── Redis hit → deserialize snapshot (metric: brain.avenger.memory.hits)
└── Redis miss → rebuild from learning_events, cache for 24h
    (metric: brain.avenger.memory.misses)
        ↓
Memory hint injected into persona prompt → LLM sees reinforcement
```

**Wave A3:** `AdaptivePromptBuilder.buildReviewPatternSection(projectId)` additionally pulls the top-N APPROVED `ReviewPatternNode` entries (PR-comment patterns mined post-merge, see [WAVES.md] / Wave A3) and injects them at higher weight than vanilla `ConventionNode`s. The avenger-scoped overload appends this section after the memory hints.

---

## Example

**Session 1:** HAWKEYE reviews code with 3 separate `@Autowired` field injections. Each emits `LearningEvent` with `convention_rule = "Field injection (@Autowired) detected on X"`.

**Session 2 (next day):** HAWKEYE is asked to review more code on the same project. The persona prompt now contains:

```
--- HAWKEYE MEMORY (prior observations on this project) ---
HAWKEYE has seen 'no-field-injection' violated 3 times on this project — be strict.

CRITICAL: NEVER inject via @Autowired on fields. Use constructor injection with final fields + @RequiredArgsConstructor.
```

HAWKEYE is now stricter on this specific codebase without any manual tuning.

---

## Configuration

```yaml
brain:
  cache:
    avenger-memory-ttl-hours: ${BRAIN_CACHE_AVENGER_MEMORY_TTL_HOURS:24}
    max-memory-hint-tokens: ${BRAIN_CACHE_MAX_MEMORY_HINT_TOKENS:300}
```

`max-memory-hint-tokens` is an ORACLE-enforced upper bound on prompt growth. If a memory snapshot would exceed this, the builder truncates to the top violations by count.

---

## How Scoping Works

Memory is **per-Avenger AND per-project**. HAWKEYE's memory on `project-alpha` is completely independent from:
- HAWKEYE's memory on `project-beta`
- STARK's memory on `project-alpha`

This matches the Worker/Service/Support role taxonomy — each Avenger owns its own learning within its domain.

---

## Convention Key Normalization

Raw issue text varies ("Field injection detected on `foo`" vs "Field injection (@Autowired) on field `bar`"). [ConventionKeyExtractor](../src/main/java/com/assurant/brain/learning/ConventionKeyExtractor.java) normalizes these to stable keys like `no-field-injection`, `log4j2-usage`, `pii-leak`.

The extractor is shared between `AvengerMemory` and `AdaptivePromptBuilder` — one source of truth for what counts as "the same violation."

---

## Observability

| Metric | Type | Tags |
|--------|------|------|
| `brain.avenger.memory.hits` | Counter | `avenger` |
| `brain.avenger.memory.misses` | Counter | `avenger` |

Cache hit ratio should exceed 80% after steady-state on active projects. Missing = Redis down or cache expired.

---

## Failure Modes

- **Redis unavailable:** `AvengerMemory.getMemory` degrades gracefully — logs a warning, returns an empty snapshot. Avenger reviews proceed normally without memory hints. No cascade failure.
- **Corrupted cache entry:** JSON deserialization fails → rebuild from DB, re-cache.
- **New project (no events):** `AvengerMemorySnapshot.empty()` returned. No hint added. No failure.

---

## Verification

```bash
# Trigger repeated violations for HAWKEYE
curl -X POST http://localhost:8080/api/v1/avengers/hawkeye/review \
  -d '{"projectId":"demo","code":"<code with @Autowired field>"}' \
  -H 'Authorization: Bearer <jwt>' -H 'Content-Type: application/json'
# (repeat 3+ times)

# Verify events
psql -c "SELECT avenger_id, convention_rule FROM learning_events WHERE avenger_id='HAWKEYE' AND project_id='demo';"

# Inspect Redis cache
redis-cli GET "brain:avenger:HAWKEYE:memory:demo"

# Next review — HAWKEYE prompt will include memory hint
# (inspect token_usage_records.prompt_text)

# Metrics
curl http://localhost:8080/actuator/prometheus | grep brain_avenger_memory
```

---

*For the Avengers API see [Avengers API](AVENGERS_API.md). For the broader learning system see [Architecture](ARCHITECTURE.md).*
