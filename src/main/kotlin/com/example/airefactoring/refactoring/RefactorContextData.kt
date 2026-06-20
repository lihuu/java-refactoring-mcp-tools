package com.example.airefactoring.refactoring

/**
 * Handler-owned, bounded context that gets serialized into the LLM prompt. Each refactoring
 * supplies its own concrete shape; the agnostic [PromptEnvelope] only needs its JSON form.
 */
interface RefactorContextData {
    /** The bounded context as a JSON string to embed in the user prompt. */
    fun toPromptJson(): String
}
