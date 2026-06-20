package com.example.airefactoring.refactoring.extractmethod

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Performs the actual extract-method via the platform API. Injectable so tests can spy. */
interface ExtractMethodExecutor {
    /** Extract [elements] (from [file]) into a new method named [methodName]; returns a success summary. */
    fun extract(project: Project, file: PsiFile, elements: Array<PsiElement>, methodName: String): String
}
