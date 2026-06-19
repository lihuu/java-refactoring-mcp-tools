package com.example.airefactoring.llm

import com.example.airefactoring.settings.AiRefactoringSettings

interface LlmClient {
    fun complete(systemPrompt: String, userPrompt: String, settings: AiRefactoringSettings.State): String
}
