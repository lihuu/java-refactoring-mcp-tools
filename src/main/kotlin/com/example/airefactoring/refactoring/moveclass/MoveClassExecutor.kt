package com.example.airefactoring.refactoring.moveclass

import com.intellij.openapi.project.Project

interface MoveClassExecutor {
    suspend fun move(
        project: Project,
        preparation: MoveClassPreparation,
    ): MoveClassExecutionResult
}
