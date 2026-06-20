package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.refactoring.RefactorContextData
import com.intellij.psi.PsiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Context for an extract-method refactoring. The serializable fields are what the LLM sees; the
 * transient [elements] are the resolved selection carried to execute() (the orchestrator never
 * inspects this type — it is the handler's own). [elements] is intentionally excluded from JSON.
 */
class ExtractMethodContext(
    val data: SerializableData,
    val elements: Array<PsiElement>,
) : RefactorContextData {
    @Serializable
    data class SerializableData(
        val language: String,
        val filePath: String,
        val enclosingClass: String?,
        val enclosingMethod: String?,
        val selectedCode: String,
    )
    override fun toPromptJson(): String = PROMPT_JSON.encodeToString(data)
    companion object {
        private val PROMPT_JSON = Json { prettyPrint = true; encodeDefaults = false }
        const val MAX_SELECTED_CODE_CHARS = 4000
    }
}
