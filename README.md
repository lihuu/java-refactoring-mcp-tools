# IntelliJ Refactoring MCP Tools

An IntelliJ IDEA plugin that exposes native IntelliJ refactorings to AI coding agents through the
IDE's built-in MCP Server, so agents plan and get approval before any source change — the plugin
never edits text directly and never calls an LLM itself.

The validation release exposes exactly one tool: **`java_extract_method`**.

## Requirements

- JDK 25 (toolchain) — the plugin compiles with JDK 25 but targets Java 21 bytecode, so it still
  runs inside IntelliJ IDEA 2026.1.3 (JBR 21).
- IntelliJ IDEA 2026.1.3 (build 261) — the plugin is built against the platform 2026.1.3 and
  depends on the bundled `com.intellij.mcpServer` and `com.intellij.java` plugins.

## Architecture

```text
Codex (or another MCP client)
        |
        | java_extract_method
        v
IntelliJ MCP Server
        |
        v
java_extract_method (ExtractMethodMcpToolset)
        |
        v
Native IntelliJ Extract Method processor
        |
        v
PSI-aware source change grouped as one IDE command
```

The MCP Server owns transport, authentication, project routing, JSON Schema generation, and client
connectivity. The plugin owns input validation, current-PSI resolution, and native refactoring
execution.

## Enabling the MCP tool

1. Run the plugin in a sandbox IDE: `./gradlew runIde`.
2. With the plugin loaded, `java_extract_method` is registered in the built-in MCP Server and
   appears in **Settings | Tools | MCP Server | Exposed Tools**, where it can be enabled or
   disabled.
3. Connect an MCP client (e.g. Codex) to that sandbox MCP Server and confirm the tool is
   discoverable (Codex: `/mcp`).

## Tool contract

### Input

| Field | Type | Meaning |
|---|---|---|
| `pathInProject` | string | Java file path relative to the selected IntelliJ project root. |
| `startLine` | integer | 1-based inclusive start line. |
| `startColumn` | integer | 1-based inclusive start column. |
| `endLine` | integer | 1-based line containing the exclusive end position. |
| `endColumn` | integer | 1-based exclusive end column. |
| `methodName` | string | New lower-camel-case Java method name. |

Positions are **1-based**, the start is **inclusive**, and the end is **exclusive**: the selected
text runs from `startLine:startColumn` through (not including) `endLine:endColumn`. Project routing
is supplied by the MCP host, so the tool does not take a project path argument.

The tool ignores the user's current editor selection — it resolves the supplied range against the
latest document and PSI state on every call.

### Success response

On success the tool returns a JSON envelope that echoes which project the refactoring ran in, so a
multi-project setup stays auditable regardless of which MCP client made the call:

```json
{
  "ok": true,
  "operation": "java_extract_method",
  "filePath": "src/main/java/example/Calc.java",
  "projectBasePath": "/home/example",
  "methodName": "calculateTotal",
  "summary": "Extracted method 'calculateTotal'."
}
```

- `filePath` is the edited file path relative to the target project root.
- `projectBasePath` is the absolute path of the IntelliJ project that hosted the refactoring — use it
  to confirm which project was actually modified when multiple projects are open in the same IDE.

### Project routing

Project routing is handled by the MCP host, not by the plugin. The host resolves the target project
from the client-supplied `projectPath` (injected into every tool's schema as implicit metadata); if
none is supplied and exactly one project is open, that project is used. With multiple projects open
and no matching `projectPath`, the host rejects the call rather than silently acting on a random
project. The plugin's success envelope echoes the resolved project via `projectBasePath` so the
actual target is always visible to the caller.

### Plan-first usage

Codex drives multi-method decompositions conversationally:

> "This `calculateTotal` method is doing too much. Split it into a discount calculation, a shipping
> calculation, and the total."

The agent reads the complete method, proposes the child methods, their responsibilities, and the
extraction order, then **waits for explicit user approval** before changing any source. For each
approved child method it calls `java_extract_method` once, re-reads the modified file afterwards
(because line and column positions change after every extraction), and after the final extraction
runs IDE diagnostics and a build.

### Error codes

Failures return `{"ok": false, "code": "...", "message": "..."}` with one of these stable codes:

| Code | Meaning |
|---|---|
| `FILE_NOT_FOUND` | The project-relative path does not resolve to a file. |
| `OUTSIDE_PROJECT` | The path is absolute or resolves outside the project root. |
| `NOT_JAVA_FILE` | The target file is not a Java file. |
| `READ_ONLY` | The target file is read-only. |
| `INVALID_RANGE` | Positions are out of bounds, or the range is empty or reversed. |
| `INVALID_METHOD_NAME` | The requested method name is not a valid Java identifier. |
| `NO_EXTRACTABLE_ELEMENTS` | The range does not resolve to an expression or statement block. |
| `PREPARE_FAILED` | The native processor refused the selection. |
| `REFACTORING_FAILED` | An unexpected failure occurred during execution. |

## Development

```bash
./gradlew test          # run tests only
./gradlew buildPlugin   # produce a distributable .zip in build/distributions/
./gradlew runIde        # launch a sandbox IntelliJ with the plugin loaded
```

The Gradle wrapper is committed. The toolchain is JDK 25 (set `javaVersion` in
`gradle.properties`), but the bytecode target stays at Java 21: IntelliJ 2026.1 runs on JBR 21,
and Kotlin 2.4's `jvmTarget` tops out at JVM 24, so JDK 25 bytecode would neither compile
(jvmTarget ceiling) nor load in the IDE. The `jvmTarget`/Java `release` are pinned to 21 in
`build.gradle.kts`.

## Manual end-to-end acceptance

The acceptance scenario uses the fixture `src/test/testData/mcp/ComplexMethod.java` as a stable
Java method to analyze. Record pass/fail for each step:

- [ ] Launch a sandbox IDEA 2026.1.3 with the plugin.
- [ ] Confirm `java_extract_method` appears in MCP Server **Exposed Tools**.
- [ ] Connect Codex to that sandbox MCP Server and confirm `/mcp` discovers the tool.
- [ ] Ask Codex in natural language to analyze a complex Java method and split it into child methods.
- [ ] Confirm Codex presents a decomposition plan and waits.
- [ ] Approve one extraction and confirm the recorded write tool is `java_extract_method`, with no
      patch, text replacement, or generated whole-file rewrite.
- [ ] Confirm IntelliJ creates the helper method and updates the original method correctly.
- [ ] Run IDE diagnostics and the project build successfully.
- [ ] Confirm one IDEA Undo reverses the extraction.

## Known limitations

- **Java only.** The tool rejects non-Java files; Kotlin and other languages are out of scope for
  this release.
- **One extraction per call.** A multi-method decomposition requires one `java_extract_method` call
  per child method, with a file re-read in between.
- **No preview UI.** The tool runs headless; there is no interactive refactoring dialog or preview
  during an MCP call.
- **No text fallback.** If the native refactoring refuses a selection, the tool reports the failure
  and never edits source text directly.
