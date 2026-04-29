# Guardrails

> **Who this document is for:** Developers adding new LLM call sites to Project Brain, or adding new guardrail rules. Written for someone familiar with Spring Boot but new to guardrail frameworks.

Project Brain uses a composable guardrail chain to protect every LLM call from prompt injection, PII leakage, and malformed responses. The design is informed by [NeMo Guardrails](https://arxiv.org/abs/2310.10501) (NVIDIA's 5-rail architecture) and [JGuardrails](https://dev.to/ratila/jguardrails-production-ready-safety-rails-for-java-llm-applications-2aee) (Java-native PASS/BLOCK/MODIFY decision model).

---

## Architecture

Every LLM-calling service runs user input through `RailChain.applyPreLlm()` before sending to the model, and runs the LLM response through `RailChain.applyPostLlm()` before parsing.

```
User input → RailChain.applyPreLlm() → LLM → RailChain.applyPostLlm() → Parsed result
             (sanitize, block bad)              (validate schema, block leaks)
```

Rails execute in **ascending priority order** — cheap checks first, expensive ones later.

## Decision Model

Each rail returns a `RailResult` with one of three decisions:

| Decision | Effect |
|----------|--------|
| `PASS` | Input flows unchanged to next rail |
| `BLOCK` | Chain halts; `RailBlockedException` thrown → HTTP 400 |
| `MODIFY` | Input transformed; sanitized payload flows to next rail |

## Built-in Rails

### Pre-LLM (input side)

| Rail | Priority | Type | What it does |
|------|----------|------|--------------|
| `InputLengthRail` | 10 | LENGTH | Rejects input exceeding `brain.guardrails.max-input-chars` |
| `PromptInjectionRail` | 20 | INJECTION | Delegates to `PromptInjectionClassifier`; default regex from `resources/guardrails/prompt-injection-patterns.txt` |
| `PiiMaskRail` | 30 | PII | Masks SSN / email / phone / credit card / IBAN / JWT / IPv4. MODIFY decision by default; BLOCK if `brain.guardrails.block-on-pii-detection=true` |

### Post-LLM (output side)

| Rail | Priority | Type | What it does |
|------|----------|------|--------------|
| `OutputSchemaRail` | 10 | SCHEMA | Validates LLM JSON against `networknt/json-schema-validator`. Schema path passed via context metadata key `outputSchemaResource` |
| `OutputPiiRail` | 20 | PII | Defense-in-depth: blocks if LLM leaks SSN / credit card / JWT in its response |
| `OutputLengthRail` | 30 | LENGTH | Caps response size at `brain.guardrails.max-output-chars` |
| `ContextualGroundingRail` (Wave R6) | 60 | GROUNDING | When `brain.llm.provider=bedrock`, scores LLM output token-overlap against retrieved RAG sources passed via context metadata key `groundingSources`. Below threshold (default 0.7, override via `groundingThreshold` metadata) → BLOCK. No-op for other providers. |

---

## Configuration

All guardrail settings live under `brain.guardrails.*` in `application.yml`:

```yaml
brain:
  guardrails:
    max-input-chars: ${BRAIN_GUARDRAILS_MAX_INPUT_CHARS:20000}
    max-prompt-chars: ${BRAIN_GUARDRAILS_MAX_PROMPT_CHARS:50000}
    max-output-chars: ${BRAIN_GUARDRAILS_MAX_OUTPUT_CHARS:30000}
    block-on-pii-detection: ${BRAIN_GUARDRAILS_BLOCK_ON_PII:false}
    strict-schema: ${BRAIN_GUARDRAILS_STRICT_SCHEMA:true}
    injection-classifier: ${BRAIN_GUARDRAILS_INJECTION_CLASSIFIER:REGEX}
```

---

## Wiring a New LLM Call Site

1. Inject `RailChain` via constructor:
   ```java
   private final RailChain railChain;
   ```

2. Run pre-LLM rails on user input:
   ```java
   RailChain.ChainResult preLlm = railChain.applyPreLlm(
           RailContext.preLlm(projectId, "MyService", userInput));
   String sanitizedInput = preLlm.sanitized();
   ```

3. Call the LLM with the sanitized input.

4. Run post-LLM rails on the response (optionally pass a schema path):
   ```java
   railChain.applyPostLlm(RailContext.postLlm(projectId, "MyService", llmResponse,
           Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, "schemas/my-response.json")));
   ```

If any rail blocks, a `RailBlockedException` is thrown and caught by `GlobalExceptionHandler` as HTTP 400.

### LLM call sites currently wired through RailChain

| Service | Pre-LLM | Post-LLM | Schema |
|---------|---------|----------|--------|
| `PlannerService.generatePlan` | ✓ | ✓ | `schemas/plan.json` |
| `PlannerService.generateMultiRepoPlan` | ✓ | ✓ | `schemas/multi-repo-plan.json` |
| `PlannerService.generateExplanation` | ✓ | ✓ | none |
| `ClarifierService.scoreConfidence` | ✓ | ✓ | `schemas/clarifier-response.json` |
| `DocGeneratorService.generate` | ✓ | ✓ | none — narrative output |
| `CodeGeneratorService.generateCode` | ✓ | ✓ | none |
| `TicketProposalService.proposeTickets` | ✓ | ✓ | `schemas/tickets.json` |

The five UX-Q2.2 doc-bundle sections each go through `DocGeneratorService.generate`, so every section call is rail-gated even though the user prompt is empty.

---

## Adding a Custom Rail

Implement `Rail`:

```java
@Component
@RequiredArgsConstructor
public class MyCustomRail implements Rail {
    @Override public RailResult apply(RailContext ctx) { /* ... */ }
    @Override public RailPhase phase() { return RailPhase.PRE_LLM; }
    @Override public int priority() { return 50; }
    @Override public RailType type() { return RailType.TOPIC; }
}
```

Spring auto-injects it into `RailChain` at startup. Pick a priority slot that makes sense for cost: cheap first, expensive last.

---

## Swapping the Injection Classifier

The default `RegexInjectionClassifier` loads patterns from `resources/guardrails/prompt-injection-patterns.txt`. To swap in a model-based classifier (e.g., [StackOne Defender](https://www.strathweb.com/2026/03/introducing-agentguard-declarative-guardrails-for-dotnet-ai-agents/) ONNX model — ~22MB, ~8ms inference, F1 ~0.97):

1. Implement `PromptInjectionClassifier` with your ONNX runtime.
2. Mark it `@Component @Primary`.
3. Set `brain.guardrails.injection-classifier=ONNX` (enum value, for observability).

---

## Observability

Every rail evaluation emits a Micrometer counter:

```
brain_guardrail_outcome_total{rail="INJECTION",decision="BLOCK"}  3
brain_guardrail_outcome_total{rail="PII",decision="MODIFY"}      12
brain_guardrail_outcome_total{rail="LENGTH",decision="PASS"}    487
```

Scrape at `/actuator/prometheus`.

---

*For architecture context see [Architecture](ARCHITECTURE.md). For testing, see [Testing](TESTING.md).*
