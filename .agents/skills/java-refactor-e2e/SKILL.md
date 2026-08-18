---
name: java-refactor-e2e
description: Use when a change affects this repository's Java MCP refactoring tools, IntelliJ native refactoring execution, MCP schema or registration, or when real IDEA MCP end-to-end acceptance is requested.
---

# Java Refactor E2E

## Overview

Use the repository-owned disposable Java project and dedicated IDEA sandbox. This is the real MCP
acceptance path; `./gradlew test` alone is not evidence that Codex can discover and invoke the
installed tool in IDEA.

## Start the environment

1. Inspect the current diff. Run this flow when it changes a Java MCP tool, resolver, executor,
   native processor integration, registration, or result/schema behavior. Skip it for unrelated
   documentation-only changes.
2. Run `./gradlew runE2eIde`. It resets the fixture at `build/e2e-workspace`, starts the current
   plugin in `build/e2e-sandbox`, opens that project, enables the built-in MCP Server on port 3001,
   and trusts only this generated project.
3. Wait for IDEA and the MCP endpoint to become ready; do not use a fixed sleep. Connect to
   `http://127.0.0.1:3001/mcp` and retain one MCP session for the whole acceptance run.
4. Call `tools/list`. Require the changed Java tool and its current schema before attempting a
   mutation. Missing tools are an acceptance failure, never a reason to use patches or another
   editing mechanism.

The fixture template is never edited directly. Re-run `prepareE2eFixture` (or restart
`runE2eIde`) before each independent scenario.

## Select a representative scenario

| Capability | Fixture target |
|---|---|
| Extract Method / Introduce Variable / Inline Variable | `RefactoringSamples.calculateTotal` |
| Introduce Constant / Introduce Field | literals `12` or `"order-"` in `RefactoringSamples` |
| Change Signature / Introduce Parameter | `RefactoringSamples.formatLabel` or `calculateTax` |
| Safe Delete | `RefactoringSamples.unusedHelper` |
| Move Instance Method | `Invoice.applyDiscount(Customer)` and `Checkout.charge` |
| Future field encapsulation | `RefactoringSamples.mutableCount` |

Read the target and relevant caller files before calculating the current 1-based coordinates. The
MCP host requires `projectPath = build/e2e-workspace`; use only native Java MCP refactoring tools
for mutations.

## Verify the real chain

After every successful call:

1. Re-read each returned affected file and confirm the expected native structural result and
   cross-file caller changes.
2. Run IDEA diagnostics, then `./gradlew -p build/e2e-workspace check`.
3. Inspect `build/e2e-sandbox/log_runE2eIde` for errors from the plugin, MCP Server, native handler, EDT,
   cancellation, or dialogs.
4. Undo once in IDEA and verify the changed fixture files match their state immediately before the
   MCP call.

For an expected native refusal, require `ok=false`, no source mutation, and no dialog. Do not
replace a rejected refactoring with text edits, PSI mutation, another IDE action, or a different
tool.

## Report

Report only observed facts: the tested tool/schema, fixture files, tool response, diagnostics/build
result, log result, and Undo result. If IDEA, MCP connectivity, or tool exposure is unavailable,
report that exact blocker and preserve the fixture; do not claim end-to-end acceptance.
