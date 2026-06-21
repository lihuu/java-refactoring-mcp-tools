# AI Refactoring MVP

IntelliJ IDEA plugin that uses an LLM to suggest Java refactorings, then performs them via IntelliJ's native refactoring API. The AI never edits source directly. Refactorings are pluggable behind a single `RefactoringHandler` extension point (see [Architecture](#architecture)); the ones shipped today rename a Java local variable or field, extract a selected block into a new method, and introduce a parameter object for a method's parameters. See the [Roadmap](#roadmap) for where this is going.

## Roadmap

The plugin is built smell-first: refactoring is driven by code **bad smells** (Fowler's
catalog), where each smell maps to one or more refactoring handlers. The full direction and
the 24-smell catalog live in [`docs/design/`](docs/design/). The near-term work is the
**starter set** — a minimal set of smells chosen to exercise every part of the pipeline
(hard-rule detection, AI-semantic detection, a brand-new handler, and reusing an existing
handler) before fanning out to the rest.

### Done

- **Pluggable architecture.** One `RefactoringHandler` = one refactoring; the shared
  orchestrator (`AbstractAiRefactorAction`) owns the pipeline, handlers own their vertical slice.
- **Per-refactoring entry points.** Each refactoring has its own reliable AI-triggered action,
  so basic refactorings are usable today.
- **OpenAI SDK integration.** Talks to any OpenAI-compatible endpoint via the official
  `com.openai:openai-java` SDK.
- **Non-blocking + crash-safe pipeline.** The LLM call runs on a background `Task.Backgroundable`
  (the UI thread never blocks); a top-level guard surfaces unexpected failures as notifications.
- **Three shipped handlers:**
  - **Rename Symbol** — Mysterious Name → Rename (AI-semantic detection).
  - **Extract Method** — Long Function / Duplicated Code → Extract Method (selection-based).
  - **Introduce Parameter Object** — Long Parameter List → Introduce Parameter Object
    (hard-rule detection: applies only when a method has ≥ 3 parameters; all params folded,
    AI only names the class).

### Next (starter set, remaining)

- **Duplicated Code → Extract Method** — a new *detection* reusing the existing Extract handler;
  validates "one smell reuses an existing action" and the IntelliJ-inspection detection path.

### Later (planned, not started)

- **Smell detection layer.** Reuse IntelliJ's built-in inspections to *locate* smells
  (Long Method, Long Parameter List, duplicates …) so hard-detectable smells no longer need
  the user to pick the code. AI stays limited to naming and fuzzy-smell confirmation.
- **File-level multi-step analysis.** Point at a file → analyze → emit an ordered sequence of
  refactoring actions → apply step by step. Uses **semantic anchors** (not `PsiElement`/offsets)
  re-resolved before each step, because each apply can invalidate later steps' PSI.
- **More handlers** from the catalog's second tier (Feature Envy → Move Method, Data Clumps,
  Message Chains → Hide Delegate, …).

> Design docs: [`docs/design/2026-06-20-smell-driven-refactoring-direction.md`](docs/design/2026-06-20-smell-driven-refactoring-direction.md)
> (direction, three-tier triage, locked decisions) and
> [`docs/design/refactoring-smells-catalog.md`](docs/design/refactoring-smells-catalog.md)
> (the 24 smells).

## Requirements

- JDK 21
- Gradle 9.6 (the wrapper handles this once initialized)
- IntelliJ IDEA 2026.1+ for development

This project uses the **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform` 2.16.0), Kotlin 2.4, and targets IntelliJ IDEA Community 2026.1.3.

> **JDK version note:** the build targets JDK 21 (`javaVersion` in `gradle.properties`). IntelliJ IDEA 2026.1 runs on the JetBrains Runtime (JBR 21), and Kotlin 2.4's `jvmTarget` tops out at JVM 24 — so a newer JDK such as 25 would neither compile nor load in the IDE. 21 is the latest usable target for this platform.

## Development

```bash
./gradlew build           # compile and run tests
./gradlew test            # run tests only
./gradlew runIde          # launch a sandbox IntelliJ with the plugin
./gradlew buildPlugin     # produce a distributable .zip in build/distributions/
```

## Architecture

The plugin is organized around a single extension point: a **`RefactoringHandler`**. Each
refactoring has its own thin action (e.g. "AI Rename Symbol", "AI Extract Method", "AI
Introduce Parameter Object") that binds exactly one handler to a *generic, refactoring-agnostic
orchestrator* (`AbstractAiRefactorAction`). The orchestrator knows nothing about any specific
refactoring — it runs a fixed pipeline and delegates every refactoring-specific decision to
its handler:

```
action/AbstractAiRefactorAction    generic orchestrator (Java guard → resolve → config →
        │                          prompt → LLM → decode → validate → execute → notify)
        │
        ├─ action/AiRenameSymbolAction            binds RenameSymbolHandler
        ├─ action/AiExtractMethodAction           binds ExtractMethodHandler
        ├─ action/AiIntroduceParameterObjectAction binds IntroduceParameterObjectHandler
        │
        ├─ refactoring/RefactoringHandler          the extension point (one impl = one refactoring)
        ├─ refactoring/PromptEnvelope              shared, refactoring-agnostic prompt preamble
        ├─ refactoring/rename/RenameSymbolHandler                      renames a local var/field
        ├─ refactoring/extractmethod/ExtractMethodHandler             extracts a selection into a method
        └─ refactoring/introduceparameterobject/IntroduceParameterObjectHandler  folds params into an object
```

Each refactoring is reached through its **own entry point**, so the AI trigger for each is
reliable and unambiguous (the user picks the refactoring by choosing the menu item). A
`RefactoringRegistry` (ordered, first-`resolve()`-wins) also exists, reserved for a future
auto-analysis entry point that detects a smell and picks the handler — it is not used by the
manual per-refactoring actions.

A `RefactoringHandler` owns its whole vertical slice:

| Method | Responsibility |
|---|---|
| `resolve(file, offset)` | "Do I apply at this caret?" → a `RefactorTarget`, or `null` to pass |
| `promptContribution(target)` | this refactoring's prompt rules + the JSON shape it expects |
| `parse(actionJson)` | decode the LLM's action object into a `RefactorOperation` |
| `validate(op, target, project)` | check the operation before applying it |
| `execute(op, target, project, settings)` | apply it (via the native refactoring API) and return a success summary |

The orchestrator handles only the shared concerns: the Java-file precondition, the
`no_action` response, configuration checks, the LLM call, and notifications. The shared
prompt preamble ("you must not edit code / return only JSON …") lives in `PromptEnvelope`;
each handler contributes only its own rules.

### Adding a refactoring

1. Write a class implementing `RefactoringHandler` (e.g. under `refactoring/extractconstant/`).
   Its `id` must equal the `"action"` string it emits/consumes in the LLM JSON.
2. Add a thin action (e.g. `AiExtractConstantAction`) that binds the handler to
   `AbstractAiRefactorAction`, mirroring `AiExtractMethodAction`.
3. Register the action in `META-INF/plugin.xml` (add it to `EditorPopupMenu` + `CodeMenu`).

No change to `AbstractAiRefactorAction` or the LLM/notify layers is required. Reusable building
blocks (`SymbolResolver`, `ContextCollector`, `NameValidator`, the executors) remain composable
by a handler — they are not referenced by the orchestrator.

## Configuration

The plugin talks to the LLM through the official **OpenAI Java SDK** (`com.openai:openai-java:4.41.0`), pointed at any OpenAI-compatible endpoint via a custom `baseUrl`.

After installing or running in sandbox, open **Settings → Tools → AI Refactoring** and set:
- API Base URL — the API root (e.g. `https://api.openai.com`). The `/v1` version segment is appended automatically.
- API Key
- Model (e.g. `gpt-4o-mini`)
- Enable Preview (toggle the rename preview dialog)

## Supported refactorings

- Java files only.
- **Rename** — a local variable or field under the caret.
- **Extract Method** — a selection (or the statement at the caret) into a new method.
- **Introduce Parameter Object** — folds the parameters of the method under the caret (≥ 3 parameters) into a new class.
- API key is stored in the plugin's settings file (not the OS keychain). Do not commit it.
- One LLM endpoint shape (OpenAI-compatible chat/completions via the official OpenAI Java SDK).

## Manual sandbox verification (acceptance)

Run `./gradlew runIde` and execute each scenario. Record pass/fail. (Requires Gradle 9.6 + JDK 21; the Gradle wrapper must be generated first — see Development.)

- [ ] Java local variable rename — caret on a local var declaration, AI returns rename, native preview/dialog appears (if Enable Preview is on), variable + usages update.
- [ ] Java field rename — same flow on a private/public field.
- [ ] Extract method — select a statement block, "AI Extract Method", AI returns a method name, the block is extracted.
- [ ] Introduce parameter object — caret in a method with ≥ 3 parameters, "AI Introduce Parameter Object", AI returns a class name, parameters are folded into the new class.
- [ ] AI returns no_action — notification reads "No refactoring suggested.", source unchanged.
- [ ] AI returns invalid JSON — notification reads "AI response is invalid.", no refactoring invoked.
- [ ] Non-Java file (e.g. notes.txt) — notification reads "AI Refactoring MVP only supports Java files.", LLM not called.
- [ ] Missing API key — notification reads "AI Refactoring is not configured. Set base URL, API key, and model in Settings.", LLM not called.
- [ ] Slow/unreachable endpoint — the IDE stays responsive (a cancellable "AI analyzing code…" background indicator appears), and a failure shows a balloon rather than freezing.

Each scenario must leave the IDE stable. AI must not edit source outside the native refactoring path.

## Known limitations

- **Caret must be on the symbol's declaration, not a usage.** The rename handler maps the caret's identifier to its declaration only when the caret sits on the declaration. A caret on a *reference* (a use site) does not resolve to a supported symbol. A production version should resolve references to their target declaration.
- **API key is stored in plain settings XML**, not the OS keychain (see [Supported refactorings](#supported-refactorings) above).

> Two earlier MVP limitations have since been resolved: the LLM call now runs on a background
> thread (the UI no longer blocks), and a top-level error guard surfaces unexpected failures as
> notifications. See the [Roadmap](#roadmap).
