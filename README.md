# AI Refactoring MVP

IntelliJ IDEA plugin that uses an LLM to suggest Java symbol renames, then performs them via IntelliJ's native rename refactoring. The AI never edits source directly.

## Requirements

- JDK 17
- Gradle 8.7 (the wrapper handles this once initialized)
- IntelliJ IDEA 2024.1+ for development

## Development

```bash
./gradlew build           # compile and run tests
./gradlew test            # run tests only
./gradlew runIde          # launch a sandbox IntelliJ with the plugin
./gradlew buildPlugin     # produce a distributable .zip in build/distributions/
```

> **Note:** The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) was not generated in this initial bootstrap because `gradle` is not available on the bootstrap host's `PATH`. To generate it, run `gradle wrapper --gradle-version 8.7 --distribution-type bin` once on a machine with Gradle 8.7+ installed (or use any IDE that auto-provisions the wrapper).

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
