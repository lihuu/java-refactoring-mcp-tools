package com.example.airefactoring.refactoring.introducemember

import com.example.airefactoring.refactoring.SourceRange
import com.intellij.openapi.project.Project

/** Public facade for the MCP `java_introduce_field` tool. Contains no policy. */
class IntroduceFieldOperation(
    resolver: IntroduceMemberSelectionResolver = IntroduceMemberSelectionResolver(),
    executor: IntroduceMemberExecutor = IntellijIntroduceMemberExecutor(),
) {
    private val delegate = IntroduceMemberOperation(
        IntroduceMemberProfile.InstanceFinalField,
        resolver,
        executor,
    )

    suspend fun execute(
        project: Project,
        pathInProject: String,
        range: SourceRange,
        preferredName: String,
        targetClassQualifiedName: String? = null,
    ): String = delegate.execute(
        project,
        pathInProject,
        range,
        preferredName,
        targetClassQualifiedName,
    )
}
