# AI Refactoring MVP

IntelliJ IDEA plugin that uses an LLM to suggest Java refactorings, then performs them via IntelliJ's native refactoring API. The AI never edits source directly. Refactorings are pluggable behind a single `RefactoringHandler` extension point (see [Architecture](#architecture)); the one shipped today renames a Java local variable or field.

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

The plugin is organized around a single extension point: a **`RefactoringHandler`**. The
action that fires on "AI Rename Symbol" is a *generic, refactoring-agnostic orchestrator* —
it does not know anything about renaming. It runs a fixed pipeline and delegates every
refactoring-specific decision to the handler that claims the caret:

```
action/AiRenameSymbolAction        generic orchestrator (Java guard → resolve → config →
        │                          prompt → LLM → decode → validate → execute → notify)
        │
        ├─ refactoring/RefactoringRegistry      ordered handlers; first whose resolve() matches wins
        ├─ refactoring/RefactoringHandler        the extension point (one impl = one refactoring)
        ├─ refactoring/PromptEnvelope            shared, refactoring-agnostic prompt preamble
        └─ refactoring/rename/RenameSymbolHandler   the only handler today; renames a local var/field
```

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
2. Register it in the `RefactoringRegistry` the action constructs (handlers are tried in
   order; the first whose `resolve()` returns non-null claims the caret).

No change to `AiRenameSymbolAction` or the LLM/notify layers is required. Rename-specific
stages (`SymbolResolver`, `ContextCollector`, `NameValidator`, `IntellijRenameExecutor`)
remain reusable building blocks that a handler composes — they are not referenced by the
orchestrator.

> **Note:** `RefactorTarget.element` is a `PsiNamedElement`, which fits all current
> symbol-based refactorings. A future refactoring whose target is a non-named expression
> (e.g. extract-constant) would widen this field to `PsiElement` — the natural next seam.

## Configuration

The plugin talks to the LLM through the official **OpenAI Java SDK** (`com.openai:openai-java:4.41.0`), pointed at any OpenAI-compatible endpoint via a custom `baseUrl`.

After installing or running in sandbox, open **Settings → Tools → AI Refactoring** and set:
- API Base URL — the API root (e.g. `https://api.openai.com`). The `/v1` version segment is appended automatically.
- API Key
- Model (e.g. `gpt-4o-mini`)
- Enable Preview (toggle the rename preview dialog)

## MVP limitations

- Java files only.
- Only local variables and fields under the caret are supported.
- API key is stored in the plugin's settings file (not the OS keychain). Do not commit it.
- One LLM endpoint shape (OpenAI-compatible chat/completions via the official OpenAI Java SDK).

## Manual sandbox verification (MVP acceptance)

Run `./gradlew runIde` and execute each scenario. Record pass/fail. (Requires Gradle 9.6 + JDK 21; the Gradle wrapper must be generated first — see Development.)

- [ ] Java local variable rename — caret on a local var declaration, AI returns rename, native preview/dialog appears (if Enable Preview is on), variable + usages update.
- [ ] Java field rename — same flow on a private/public field.
- [ ] AI returns no_action — notification reads "No refactoring suggested.", source unchanged.
- [ ] AI returns invalid JSON — notification reads "AI response is invalid.", no rename invoked.
- [ ] Non-Java file (e.g. notes.txt) — notification reads "AI Refactoring MVP only supports Java files.", LLM not called.
- [ ] Missing API key — notification reads "AI Refactoring is not configured. Set base URL, API key, and model in Settings.", LLM not called.

Each scenario must leave the IDE stable. AI must not edit source outside the rename refactoring path.

## Known limitations (MVP / to address in MVP+)

These are deliberate trade-offs in the MVP, surfaced during code review:

- **The LLM call runs on the UI thread.** `actionPerformed` invokes the network call synchronously, so the IDE UI is blocked while waiting for the LLM (up to the 60-second request timeout). A production version should run the LLM call on a background thread (e.g. `Task.Backgroundable`) and marshal the result back to the EDT for the rename. This does not affect correctness, only responsiveness.
- **Caret must be on the symbol's declaration, not a usage.** The rename handler maps the caret's identifier to its declaration only when the caret sits on the declaration. A caret on a *reference* (a use site) does not resolve to a supported symbol, so the action reports "No supported refactoring for the symbol under the caret." A production version should resolve references to their target declaration.
- **No top-level error guard.** If an unexpected exception occurs after symbol resolution (e.g. during context collection or validation), it is logged by the IDE but not surfaced as a user notification. A production version should wrap the pipeline in a catch that shows a generic failure balloon.
- **API key is stored in plain settings XML**, not the OS keychain (see MVP limitations above).
