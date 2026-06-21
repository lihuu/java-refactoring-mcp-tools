package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.refactoring.RefactorContextData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Context for an introduce-parameter-object refactoring. The serializable fields are what the LLM
 * sees. No transient PSI is carried — execute() acts on the target's [PsiMethod] element directly.
 */
class IntroduceParameterObjectContext(val data: SerializableData) : RefactorContextData {
    @Serializable
    data class SerializableData(
        val language: String,
        val filePath: String,
        val enclosingClass: String?,
        val methodName: String,
        val methodSignature: String,
        val parameters: List<String>,
    )
    override fun toPromptJson(): String = PROMPT_JSON.encodeToString(data)
    companion object {
        private val PROMPT_JSON = Json { prettyPrint = true; encodeDefaults = false }
    }
}
