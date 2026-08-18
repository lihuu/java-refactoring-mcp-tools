package com.example.airefactoring.refactoring.makestatic

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project

/**
 * Owns current-PSI resolution, native execution, and JSON mapping for one Java Make Static request.
 * The full resolver/executor wiring is completed alongside the native executor in later tasks; the
 * tool schema and registration are established first so the MCP contract is fixed up front.
 */
class JavaMakeStaticOperation {
    suspend fun execute(
        project: Project,
        pathInProject: String,
        memberRange: SourceRange,
        replaceUsages: Boolean,
        classParameterName: String?,
        fieldParameters: List<JavaMakeStaticFieldParameter>,
        generateDelegate: Boolean,
    ): String = throw UnsupportedOperationException("Java Make Static operation is not yet wired.")
}
