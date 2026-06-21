package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod

/** Performs the actual introduce-parameter-object via the platform API. Injectable so tests can spy. */
interface IntroduceParameterObjectExecutor {
    /** Fold all of [method]'s parameters into a new class named [className]; returns a success summary. */
    fun introduce(project: Project, method: PsiMethod, className: String): String
}
