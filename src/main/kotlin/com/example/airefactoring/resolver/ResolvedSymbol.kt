package com.example.airefactoring.resolver

import com.intellij.psi.PsiNamedElement

sealed class ResolvedSymbol {
    data class Resolved(val element: PsiNamedElement, val kind: SymbolKind) : ResolvedSymbol()
    data class Unsupported(val reason: String) : ResolvedSymbol()
    object NotFound : ResolvedSymbol()
    object NotJava : ResolvedSymbol()
}
