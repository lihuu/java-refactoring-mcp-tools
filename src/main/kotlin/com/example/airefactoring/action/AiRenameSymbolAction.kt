package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.OpenAiCompatibleLlmClient
import com.example.airefactoring.refactor.IntellijRenameExecutor
import com.example.airefactoring.refactor.RenameExecutor
import com.example.airefactoring.refactoring.rename.RenameSymbolHandler

/** AI-driven rename of the Java symbol under the caret. */
class AiRenameSymbolAction(
    llmFactory: () -> LlmClient = ::OpenAiCompatibleLlmClient,
    executorFactory: () -> RenameExecutor = ::IntellijRenameExecutor,
) : AbstractAiRefactorAction(
    handler = RenameSymbolHandler(executorFactory),
    llmFactory = llmFactory,
)
