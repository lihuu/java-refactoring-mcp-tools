# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test

```bash
./gradlew test          # run tests only
./gradlew buildPlugin   # produce a distributable .zip in build/distributions/
./gradlew runIde        # launch sandbox IntelliJ with the plugin loaded
```

Tests use JUnit 4 (`LightJavaCodeInsightFixtureTestCase`). The Gradle wrapper is committed. The toolchain is JDK 25 (set `javaVersion` in `gradle.properties`), but the bytecode target stays at Java 21: IntelliJ 2026.1 runs on JBR 21, and Kotlin 2.4's `jvmTarget` tops out at JVM 24, so JDK 25 bytecode would neither compile (jvmTarget ceiling) nor load in the IDE. The `jvmTarget`/Java `release` are pinned to 21 in `build.gradle.kts`.

## Architecture

This is an IntelliJ IDEA plugin that contributes native refactorings to the IDE's built-in MCP Server. It does not call an LLM and never edits source text directly; every mutation goes through IntelliJ's native refactoring API.

### MCP registration

- The plugin depends on the bundled `com.intellij.mcpServer` and `com.intellij.java` plugins (see `META-INF/plugin.xml`).
- `ExtractMethodMcpToolset` implements `com.intellij.mcpserver.McpToolset`. Its `java_extract_method` suspend function is annotated `@McpTool` and `@McpDescription` and declares only the six client-facing arguments; the MCP host routes the resolved `Project` through the coroutine context (`com.intellij.mcpserver.project`).
- The class is registered via `<mcpServer.mcpToolset>` in `META-INF/plugin.xml`.

### Tool contract

`java_extract_method` input: `pathInProject` (project-relative Java file path), `startLine`/`startColumn` (1-based inclusive), `endLine`/`endColumn` (1-based, end exclusive), `methodName` (lower-camel-case Java identifier).

Failures return a JSON envelope `{"ok": false, "code": "...", "message": "..."}` with one of the stable codes: `FILE_NOT_FOUND`, `OUTSIDE_PROJECT`, `NOT_JAVA_FILE`, `READ_ONLY`, `INVALID_RANGE`, `INVALID_METHOD_NAME`, `NO_EXTRACTABLE_ELEMENTS`, `PREPARE_FAILED`, `REFACTORING_FAILED`. Cancellation is rethrown as `ProcessCanceledException` so the IDE and MCP client preserve cancellation semantics.

### Threading and safety rules

- All document and refactoring interaction runs on the EDT in one uninterrupted operation inside the tool's `execute`, so user edits cannot invalidate a resolved target between resolution and mutation.
- One tool call is grouped as one IDE command via `CommandProcessor`, so one Undo reverses one extraction.
- The resolver converts the 1-based range to an exclusive-end offset range, then requires an exact expression (`findExpressionInRange`) or statement block (`findStatementsInRange`) match; an empty result is rejected, never broadened or guessed.
- Method names are validated before processor execution.
- The native processor is the only mutator. If it refuses a selection, the tool returns `PREPARE_FAILED` or `REFACTORING_FAILED`; it never falls back to patches, text replacement, or direct PSI mutation.

### Package map

| Package | Purpose |
|---|---|
| `mcp/` | `ExtractMethodMcpToolset` — the MCP tool (`java_extract_method`); `McpRefactoringResult` + `McpRefactoringErrorCode` — the JSON result envelope |
| `refactoring/extractmethod/` | `ExtractMethodSelectionResolver` (source range → current PSI elements), `ExtractMethodExecutor` interface, `IntellijExtractMethodExecutor` (native processor, one-command Undo) |
| `validator/` | `NameValidator` + `ValidationResult` — rejects Java keywords, reserved words, invalid identifiers |

## Definition of done

- `./gradlew test` passes.
- `./gradlew buildPlugin` passes.
- Every write path preserves one-command Undo and goes through the native refactoring API.
