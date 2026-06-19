package com.example.airefactoring.context

import com.example.airefactoring.resolver.SymbolKind
import kotlinx.serialization.Serializable

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
)
