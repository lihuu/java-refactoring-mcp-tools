package com.example.airefactoring.refactoring.introducemember

import com.intellij.openapi.project.Project

/**
 * A closed set of fixed native Introduce Member profiles. Every profile fixes all of the
 * native refactoring's UI decisions; the MCP agent only chooses which profile to run.
 */
sealed interface IntroduceMemberProfile {
    val operationName: String
    val commandName: String
    val modifiers: List<String>

    data object Constant : IntroduceMemberProfile {
        override val operationName = "java_introduce_constant"
        override val commandName = "MCP Introduce Constant"
        override val modifiers = listOf("private", "static", "final")
    }

    data object InstanceFinalField : IntroduceMemberProfile {
        override val operationName = "java_introduce_field"
        override val commandName = "MCP Introduce Field"
        override val modifiers = listOf("private", "final")
    }
}

data class IntroduceMemberExecutionResult(
    val requestedFieldName: String,
    val actualFieldName: String,
    val fieldType: String,
    val fieldModifiers: List<String>,
    val initializationPlace: String,
    val summary: String,
)

interface IntroduceMemberExecutor {
    suspend fun introduce(
        project: Project,
        selection: IntroduceMemberSelection,
        preferredName: String,
        profile: IntroduceMemberProfile,
    ): IntroduceMemberExecutionResult
}

class IntroduceMemberPreparationException(message: String) : RuntimeException(message)
class IntroduceMemberConflictException(message: String) : RuntimeException(message)
