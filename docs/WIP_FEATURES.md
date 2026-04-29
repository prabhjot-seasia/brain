# WIP BDD Features

> **Who this document is for:** Developers running `./gradlew test` who notice scenarios tagged `@wip`. Explains which BDD features are intentionally aspirational and why.

Project Brain uses Cucumber for BDD-style acceptance testing. Most features are fully implemented with step definitions. A subset are tagged `@wip` — the feature file describes the intended behavior, but the step definitions haven't been written yet.

These scenarios are **excluded from `./gradlew test`** via `junit-platform.properties`:
```properties
cucumber.filter.tags=not @ui and not @wip
```

---

## Why @wip?

Three reasons:

1. **BDD tells a story** — writing the feature file first captures the product intent before the implementation is wired.
2. **Unit + integration tests already cover the behavior** — the functionality is tested; the BDD layer is secondary documentation.
3. **Implementing step defs is weeks of work** — the Spring context, LLM mocks, and Redis fixtures for all these scenarios would double the test suite without proportional value.

Unit tests are authoritative. BDD is aspirational documentation.

---

## Current @wip Features

| Feature | Covered by unit tests |
|---------|------------------------|
| `token-budget.feature` | `ContextWindowManagerTest`, `TokenEstimatorTest` |
| `semantic-cache.feature` | `SemanticCacheServiceTest` |
| `data-isolation.feature` | Covered implicitly via repository project-scoping tests |
| `pr-creation.feature` | `PrCreationFacadeTest`, `CodeGeneratorServiceTest` |
| `ci-remediation.feature` | `CiRemediationServiceTest`, `GitHubWebhookControllerTest` |
| `convention-learning.feature` | `MergedPrAnalyzerServiceTest`, `ConventionLearnerTest` |
| `guardrail.feature` | `RailChainTest` + 6 individual rail tests |
| `avenger-review.feature` | `AvengerReviewerTest`, `AvengerOrchestratorTest`, `AvengerControllerTest` |
| `avenger-memory.feature` | `AvengerMemoryTest`, `AdaptivePromptBuilderTest` |

---

## If You Want to Implement a @wip Feature

1. Find the feature file in `src/test/resources/features/`
2. Create a matching step definition class in `src/test/java/com/assurant/brain/bdd/steps/`
3. Implement `@Given`, `@When`, `@Then` methods matching the scenario lines
4. Remove the `@wip` tag from the feature file
5. Run `./gradlew test` — the scenarios now execute

See [AnalyzeSteps.java](../src/test/java/com/assurant/brain/bdd/steps/AnalyzeSteps.java) for a reference implementation.

---

## Removing @wip Without Implementing

**Don't.** An `@wip`-tagged feature that runs would throw `UndefinedStepException` and fail the build. The tag is the filter that keeps the build green while we document intent.

If a feature is no longer relevant, delete it entirely — don't leave orphaned scenarios.

---

*For the full testing strategy see [Testing](TESTING.md).*
