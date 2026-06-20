package com.example.airefactoring.refactoring

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared, refactoring-agnostic prompt assembly. Holds the parts of the original
 * [com.example.airefactoring.prompt.PromptBuilder] SYSTEM string that are not specific to any one
 * refactoring (the "you must not edit code / return only JSON / no prose / no code fences"
 * preamble) and assembles the final (system, user) prompt pair from a chosen handler's
 * [PromptContribution] plus the target context.
 */
object PromptEnvelope {
    private val json = Json { prettyPrint = true; encodeDefaults = false }
    const val NO_ACTION_SHAPE = """{"action":"no_action","reason":"<short explanation>"}"""

    /** Build the (system, user) prompt pair from the chosen handler's contribution and the target. */
    fun assemble(contribution: PromptContribution, target: RefactorTarget): Pair<String, String> {
        val system = buildString {
            appendLine("You are a Java refactoring planner.")
            appendLine("You must not edit code. You must not output Java code or patches. You must not output diffs.")
            appendLine("You only inspect the bounded JSON context provided. You never request external tools or network access.")
            appendLine()
            appendLine("Return only JSON. No prose. No markdown code fences. The JSON must match exactly one of these shapes:")
            appendLine("  " + contribution.jsonShapeExample)
            appendLine("  " + NO_ACTION_SHAPE)
            appendLine()
            append(contribution.systemFragment.trim())
        }
        val user = buildString {
            appendLine("Context (JSON):")
            appendLine(json.encodeToString(target.context))
            appendLine()
            appendLine("Question:")
            appendLine("Should the ${target.displayName} \"${target.context.symbolName}\" be refactored?")
            appendLine("Reply with one of the JSON shapes from the system message. No prose, no code fences.")
        }
        return system to user
    }
}
