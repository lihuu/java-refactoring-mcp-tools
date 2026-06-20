package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.OpenAiCompatibleLlmClient
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodExecutor
import com.example.airefactoring.refactoring.extractmethod.ExtractMethodHandler
import com.example.airefactoring.refactoring.extractmethod.IntellijExtractMethodExecutor

/** AI-driven extraction of the selected Java code into a new method. */
class AiExtractMethodAction(
    llmFactory: () -> LlmClient = ::OpenAiCompatibleLlmClient,
    extractExecutorFactory: () -> ExtractMethodExecutor = ::IntellijExtractMethodExecutor,
) : AbstractAiRefactorAction(
    handler = ExtractMethodHandler(extractExecutorFactory),
    llmFactory = llmFactory,
)
