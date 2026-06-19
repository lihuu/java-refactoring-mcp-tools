package com.example.airefactoring.prompt

import com.example.airefactoring.context.RefactorContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PromptBuilder {

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    private val SYSTEM = """
        You are a Java refactoring planner.
        You must not edit code. You must not output Java code or patches. You must not output diffs.
        You only inspect the bounded JSON context provided. You never request external tools or network access.

        Return only JSON. No prose. No markdown code fences. The JSON must match exactly one of these shapes:
          {"action":"rename_symbol","newName":"<identifier>","reason":"<short explanation>"}
          {"action":"no_action","reason":"<short explanation>"}

        Naming rules:
        - Local variables and fields use lowerCamelCase.
        - Classes use UpperCamelCase.
        - Methods use lowerCamelCase.
        - Never propose a Java keyword. Never propose the current name.

        Decide rename_symbol only when the new name is a clear improvement.
        When in doubt, return no_action.
    """.trimIndent()

    fun build(ctx: RefactorContext): Pair<String, String> {
        val user = buildString {
            appendLine("Context (JSON):")
            appendLine(json.encodeToString(ctx))
            appendLine()
            appendLine("Question:")
            appendLine("Should the symbol \"${ctx.symbolName}\" be renamed?")
            appendLine("Reply with one of the JSON shapes from the system message. No prose, no code fences.")
        }
        return SYSTEM to user
    }
}
