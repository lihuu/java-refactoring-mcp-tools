# Java Refactoring MCP Tools

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

- Target: IntelliJ IDEA 2026.1 (build 261) and 2026.2 (build 262); the compile SDK stays 2026.1.3
  and 262 compatibility is verified with `-PplatformVersion=2026.2.1 test` plus a
  `-PplatformVersion=2026.2.1 runE2eIde` acceptance run before release.
- JDK: 21.
- Tests: `./gradlew test`.
- Distribution: `./gradlew buildPlugin`.
- Preserve one-command Undo for every write tool.
- **Gradle/IDE process discipline:** Reuse the compatible Gradle daemon for ordinary `test` and
  `buildPlugin` invocations; do not pass `--no-daemon` unless isolation is explicitly required.
  Run Gradle test/build commands strictly one at a time and wait for the same command to exit
  before starting another—never launch a replacement merely because an agent-side wait window
  elapsed. `runE2eIde`, `runDemoIde`, and `runIde` each start a full IDEA JVM: run at most one of
  them at a time, wait for it to exit before another Gradle/IDE run, and confirm no leftover JVMs
  after acceptance. Do not stop existing daemons or kill JVMs without the user's authorization.

## Real MCP end-to-end acceptance

When a change affects an MCP tool schema/registration, a native refactoring resolver/executor, or
the user requests real acceptance, read and follow
`.agents/skills/java-refactor-e2e/SKILL.md`. It defines the disposable Java fixture, dedicated IDEA
sandbox, real MCP discovery/call checks, diagnostics/build, logs, and one-Undo verification.

## Dual distribution channels (Marketplace + GitHub)

Two long-lived branches are cut from one codebase and released in lockstep:

- `main` — the Marketplace channel. It must stay review-safe: no platform Internal API usage that
  would fail Marketplace verification. `java_introduce_parameter` (platform Internal API) lives
  only on the GitHub branch and must never be merged back into `main`. Store releases (1.0.4,
  1.0.5, ...) are cut from `main`.
- `github-distribution` — the GitHub channel: `main` plus the tools Marketplace review rejects.
  Its release versions must sort strictly above the store version they are cut from — append
  `.1` to the store version (store 1.0.4 → GitHub 1.0.4.1, store 1.0.5 → GitHub 1.0.5.1). When
  signing is used, both channels use the same Marketplace certificate so cross-channel updates
  never raise signature warnings.

Workflow:

1. All development happens on `main` through a feature branch and a pull request — direct
   pushes to `main` are blocked by a repository ruleset ("main: pull requests only"; no bypass,
   no required approvals, so the author merges their own PR). Open one PR per change, into
   `main` only — do not mirror PRs into `github-distribution`.
2. Sync `github-distribution` only when cutting a GitHub-channel release:
   `git checkout github-distribution && git merge main`. Expect conflicts in exactly three
   places: `JavaRefactorToolset.kt` / its tests / result mappings, and `gradle.properties`
   (version). Resolve by keeping both sides — the store code AND the restored tool registration.
3. Then bump `pluginVersion` to the store version + `.1`, run `./gradlew test`, `buildPlugin`,
   sign, and publish a GitHub Release with the signed zip attached. Tag the GitHub release
   `v<version>` on `github-distribution` and tag the store release `v<version>` on `main`.
4. Signing is part of the release flow. The distribution certificate lives in
   `~/.marketplace-certs` (`chain.crt`, `private.pem`, `password.txt` — a locally generated
   self-issued pair, valid through 2027-09-02; regenerate before expiry). There is no
   Marketplace portal certificate feature. Export the env vars from those files in the same
   shell before `./gradlew signPlugin`; without them the task silently SKIPS. The marketplace
   signature is embedded at ZIP level (a ~2175-byte block inserted before the central
   directory) — the plugin jars stay byte-identical between signed and unsigned builds, so
   NEVER conclude an artifact is unsigned because jar hashes match. Verify a signature by
   checking that the signed zip is ~2175 bytes larger and that the bytes just before the
   central directory contain the certificate (openssl dates, CN=lihuu).
