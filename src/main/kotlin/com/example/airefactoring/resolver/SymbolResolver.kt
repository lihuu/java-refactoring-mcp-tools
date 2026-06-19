package com.example.airefactoring.resolver

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

class SymbolResolver {
    fun resolve(file: PsiFile, caretOffset: Int): ResolvedSymbol {
        if (file !is PsiJavaFile) return ResolvedSymbol.NotJava

        val element: PsiElement = file.findElementAt(caretOffset) ?: return ResolvedSymbol.NotFound
        val named = locateNamedTarget(element) ?: return ResolvedSymbol.NotFound

        return when (named) {
            is PsiLocalVariable -> ResolvedSymbol.Resolved(named, SymbolKind.LOCAL_VARIABLE)
            is PsiField -> ResolvedSymbol.Resolved(named, SymbolKind.FIELD)
            else -> ResolvedSymbol.Unsupported(
                "AI Refactoring MVP only supports local variables and fields."
            )
        }
    }

    private fun locateNamedTarget(element: PsiElement): PsiNamedElement? {
        // Only resolve when the caret leaf is an identifier. Whitespace, keywords,
        // punctuation, and comments are not symbols and must yield NotFound rather
        // than walking up to whatever PsiNamedElement happens to enclose them.
        val identifier = element as? PsiIdentifier ?: return null
        val parent = identifier.parent
        if (parent is PsiNamedElement) return parent
        return PsiTreeUtil.getParentOfType(parent, PsiNamedElement::class.java, false)
    }
}
