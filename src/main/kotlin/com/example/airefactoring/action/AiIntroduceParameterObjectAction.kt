package com.example.airefactoring.action

import com.example.airefactoring.llm.LlmClient
import com.example.airefactoring.llm.OpenAiCompatibleLlmClient
import com.example.airefactoring.refactoring.introduceparameterobject.IntroduceParameterObjectExecutor
import com.example.airefactoring.refactoring.introduceparameterobject.IntroduceParameterObjectHandler
import com.example.airefactoring.refactoring.introduceparameterobject.IntellijIntroduceParameterObjectExecutor

/** AI-driven introduction of a parameter object for the Java method under the caret. */
class AiIntroduceParameterObjectAction(
    llmFactory: () -> LlmClient = ::OpenAiCompatibleLlmClient,
    introduceExecutorFactory: () -> IntroduceParameterObjectExecutor = ::IntellijIntroduceParameterObjectExecutor,
) : AbstractAiRefactorAction(
    handler = IntroduceParameterObjectHandler(introduceExecutorFactory),
    llmFactory = llmFactory,
)
