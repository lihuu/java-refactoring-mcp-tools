package com.example.airefactoring.refactoring

/** Thrown by [RefactoringHandler.parse] when the LLM JSON is malformed/unsupported. [userMessage] is shown to the user. */
class RefactorParseException(val userMessage: String) : RuntimeException(userMessage)
