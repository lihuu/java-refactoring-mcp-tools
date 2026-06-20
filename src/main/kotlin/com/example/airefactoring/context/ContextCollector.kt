package com.example.airefactoring.context

import com.example.airefactoring.resolver.SymbolKind
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

class ContextCollector {
    fun collect(file: PsiFile, element: PsiNamedElement, kind: SymbolKind): RefactorContext {
        val enclosingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name
        val enclosingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.name

        val symbolType = when (element) {
            is PsiVariable -> element.type.presentableText
            else -> null
        }

        val nearbySource: String = when (kind) {
            SymbolKind.LOCAL_VARIABLE -> {
                PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.body?.text
                    ?: element.text.orEmpty()
            }
            SymbolKind.FIELD -> {
                PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.text
                    ?: element.text.orEmpty()
            }
            // ContextCollector is only used by the rename handler (local var / field). METHOD is
            // never passed here; this branch only keeps the exhaustive `when` compiling.
            SymbolKind.METHOD -> element.text.orEmpty()
        }

        return RefactorContext(
            language = "java",
            filePath = file.virtualFile?.path ?: file.name,
            symbolName = element.name ?: "",
            symbolKind = kind,
            enclosingClass = enclosingClass,
            enclosingMethod = enclosingMethod,
            symbolType = symbolType,
            nearbyCode = nearbySource.take(MAX_NEARBY_CODE_CHARS),
        )
    }

    companion object {
        const val MAX_NEARBY_CODE_CHARS = 4000
    }
}
