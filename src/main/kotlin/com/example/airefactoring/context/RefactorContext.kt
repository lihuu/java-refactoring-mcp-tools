package com.example.airefactoring.context

import com.example.airefactoring.refactoring.RefactorContextData
import com.example.airefactoring.resolver.SymbolKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RefactorContext(
    val language: String,
    val filePath: String,
    val symbolName: String,
    val symbolKind: SymbolKind,
    val enclosingClass: String?,
    val enclosingMethod: String?,
    val symbolType: String?,
    val nearbyCode: String,
    val usageSummary: String? = null,
) : RefactorContextData {
    override fun toPromptJson(): String = PROMPT_JSON.encodeToString(this)

    companion object {
        private val PROMPT_JSON = Json { prettyPrint = true; encodeDefaults = false }
    }
}
