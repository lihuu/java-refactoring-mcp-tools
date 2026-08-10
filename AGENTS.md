# IntelliJ Refactoring MCP Tools

## Purpose

This plugin contributes native IntelliJ refactoring operations to the bundled MCP Server. It does
not call an LLM and must never implement refactorings through direct source-text replacement.

## Java Extract Method workflow

When a user asks to extract Java code into a method or split a complex Java method:

1. Read and analyze the complete target method and only the additional context needed.
2. Present the proposed child methods, responsibilities, and extraction order.
3. Wait for explicit user approval before changing source code.
4. Use `java_extract_method` once per approved child method.
5. Never use patches, text replacement, generated whole-file rewrites, or direct PSI mutation as a
   substitute for Extract Method.
6. Re-read the modified file after every extraction before calculating the next range.
7. Run IntelliJ diagnostics and the project build after the final extraction.
8. If the native tool rejects a selection, report the failure; do not fall back to text edits.

## Development

- Target: IntelliJ IDEA 2026.1.3, build 261.
- JDK: 21.
- Tests: `./gradlew test`.
- Distribution: `./gradlew buildPlugin`.
- Preserve one-command Undo for every write tool.
