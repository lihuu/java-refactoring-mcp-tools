package com.example.airefactoring.refactoring.rename

import com.example.airefactoring.refactoring.RefactorOperation

/** The LLM asked to rename the target symbol to [newName]. */
data class RenameOperation(val newName: String, override val reason: String?) : RefactorOperation
