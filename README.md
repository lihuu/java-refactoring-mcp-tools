# AI Refactoring MVP

IntelliJ IDEA plugin that uses an LLM to suggest Java symbol renames, then performs them via IntelliJ's native rename refactoring. The AI never edits source directly.

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

> **Note:** The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) was not generated in this bootstrap because `gradle` is not available on the bootstrap host's `PATH`. To generate it, run `gradle wrapper --gradle-version 9.6 --distribution-type bin` once on a machine with Gradle 9.6+ installed (or use any IDE that auto-provisions the wrapper).

## Configuration

After installing or running in sandbox, open **Settings → Tools → AI Refactoring** and set:
- API Base URL (e.g. `https://api.openai.com`)
- API Key
- Model (e.g. `gpt-4o-mini`)
- Enable Preview (toggle the rename preview dialog)

## MVP limitations

- Java files only.
- Only local variables and fields under the caret are supported.
- API key is stored in the plugin's settings file (not the OS keychain). Do not commit it.
- One LLM endpoint shape (OpenAI-compatible chat/completions).

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
- **Caret must be on the symbol's declaration, not a usage.** The resolver maps the caret's identifier to its declaration only when the caret sits on the declaration. A caret on a *reference* (a use site) currently resolves to the enclosing method/class and is reported as unsupported. A production version should resolve references to their target declaration.
- **No top-level error guard.** If an unexpected exception occurs after symbol resolution (e.g. during context collection or validation), it is logged by the IDE but not surfaced as a user notification. A production version should wrap the pipeline in a catch that shows a generic failure balloon.
- **API key is stored in plain settings XML**, not the OS keychain (see MVP limitations above).
