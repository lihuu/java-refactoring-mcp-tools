package com.example.airefactoring.parser

sealed class RefactorCommand {
    data class RenameSymbol(val newName: String, val reason: String?) : RefactorCommand()
    data class NoAction(val reason: String?) : RefactorCommand()
}
