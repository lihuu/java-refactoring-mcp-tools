package com.example.airefactoring.refactor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement

interface RenameExecutor {
    fun rename(project: Project, element: PsiNamedElement, newName: String, preview: Boolean)
}
