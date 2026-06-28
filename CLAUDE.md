# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test / Run

```bash
./gradlew build           # compile and run all tests
./gradlew test            # run tests only
./gradlew test --tests "com.example.airefactoring.action.AiRenameSymbolActionEndToEndTest"  # single test class
./gradlew runIde          # launch sandbox IntelliJ with the plugin loaded
./gradlew buildPlugin     # produce distributable .zip in build/distributions/
```

Tests use JUnit 4 (`LightJavaCodeInsightFixtureTestCase`). The Gradle wrapper is committed; everything needs JDK 21 (IntelliJ 2026.1's JBR runs on 21, and Kotlin 2.4's jvmTarget tops out at 24).

## Architecture

This is an IntelliJ IDEA plugin that uses an LLM to suggest Java refactorings, then applies them via IntelliJ's native refactoring API — the AI never edits source directly.

### Pipeline (orchestrator → handler)

`AbstractAiRefactorAction` is the **refactoring-agnostic orchestrator**. Each refactoring has a thin action subclass that binds exactly one `RefactoringHandler`. The orchestrator runs a fixed pipeline:

1. Java-file guard
2. `handler.resolve(file, editor, caretOffset)` → `RefactorTarget` or `null`
3. Configuration check (base URL, API key, model)
4. `PromptEnvelope.assemble(handler.promptContribution(target), target)` → (system, user)
5. `LlmClient.complete()` — the only network call, runs on `Task.Backgroundable` (never blocks EDT)
6. Parse JSON → dispatch on `"action"` field (`"no_action"` handled by orchestrator, handler's `id` routed to `handler.parse()`)
7. `handler.validate()`
8. `handler.execute()` — must call native refactoring API (no PSI mutations in the handler)
9. Notify user

The orchestrator knows nothing about any specific refactoring. Every refactoring-specific decision lives in the handler.

### RefactoringHandler (the extension point)

One `RefactoringHandler` = one AI refactoring. Adding a refactoring means writing a handler + a thin action class + registering the action in `plugin.xml` — no change to the orchestrator. The handler owns its entire vertical slice:

| Method | Responsibility |
|---|---|
| `id` | Stable string; must equal the LLM `"action"` value |
| `resolve(file, editor, caretOffset)` | "Do I apply here?" → `RefactorTarget` or `null` |
| `promptContribution(target)` | Handler-specific prompt rules + expected JSON shape |
| `parse(actionJson)` | Decode LLM's JSON into a `RefactorOperation` |
| `validate(op, target, project)` | Check the operation before applying |
| `execute(op, target, project, settings)` | Apply via native refactoring API; return success summary |

### Key design decisions

- **Per-refactoring entry points, not auto-dispatch.** Each refactoring has its own menu action (the user picks the refactoring explicitly). `RefactoringRegistry` exists but is reserved for a future auto-analysis entry point; it is not used by the current actions.
- **AI never edits source.** All mutations go through IntelliJ's refactoring API (`RenameRefactoring`, `ExtractMethodHandler`, `IntroduceParameterObjectHandler`). The LLM only produces JSON metadata (a name, a reason).
- **Prompt assembly: envelope + contribution.** `PromptEnvelope` provides the shared preamble (JSON-only, no code, no prose). Each handler contributes only its rules and JSON shape via `PromptContribution`.
- **Test seam.** `AbstractAiRefactorAction.run(project, editor, file)` is the synchronous test entry point. Actions accept `llmFactory` and `executorFactory` lambdas for injecting fakes/spies. End-to-end tests use `LightJavaCodeInsightFixtureTestCase` with `FakeLlm` and `SpyExecutor`, verifying that the pipeline reaches the executor (or doesn't) without making real network calls.
- **LlmClient is a thin interface** (`complete(system, user, settings) -> String`). The only implementation is `OpenAiCompatibleLlmClient` using the official `com.openai:openai-java` SDK pointed at any OpenAI-compatible endpoint.

### Package map

| Package | Purpose |
|---|---|
| `action/` | `AbstractAiRefactorAction` orchestrator + per-refactoring thin action subclasses |
| `refactoring/` | `RefactoringHandler` interface, `PromptEnvelope`, `PromptContribution`, `RefactorTarget`, `RefactorOperation`, `RefactoringRegistry` |
| `refactoring/rename/` | Rename handler + operation |
| `refactoring/extractmethod/` | Extract-method handler + operation + executor |
| `refactoring/introduceparameterobject/` | Introduce-parameter-object handler + operation + executor |
| `llm/` | `LlmClient` interface, `OpenAiCompatibleLlmClient`, `LlmException` |
| `settings/` | `AiRefactoringSettings` (persistent state) + configurable UI |
| `context/` | `ContextCollector` — gathers PSI context around a symbol for the LLM prompt |
| `resolver/` | `SymbolResolver` — resolves a caret offset to a `ResolvedSymbol` (local var, field, etc.) |
| `validator/` | `NameValidator` — rejects Java keywords, reserved words, invalid identifiers |
| `refactor/` | `IntellijRenameExecutor` — thin wrapper around IntelliJ's `RenameRefactoring` |
| `notify/` | `Notifier` — balloon notifications via IntelliJ's notification API |

### Three shipped handlers

1. **Rename Symbol** (`rename_symbol`) — AI-semantic detection; caret on a local var/field declaration, AI decides whether to rename.
2. **Extract Method** (`extract_method`) — selection-based; user selects a block, AI names the extracted method.
3. **Introduce Parameter Object** (`introduce_parameter_object`) — hard-rule detection (≥ 3 params); AI only names the new class.

## Dependencies

- IntelliJ Platform Gradle Plugin 2.16.0 (targets `2026.1.3`)
- Kotlin 2.4.0 + kotlinx-serialization-json 1.11.0
- `com.openai:openai-java:4.41.0` (official OpenAI Java SDK)
- JUnit 4.13.2 (platform test framework)