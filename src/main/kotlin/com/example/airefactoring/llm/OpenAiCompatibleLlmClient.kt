package com.example.airefactoring.llm

import com.example.airefactoring.settings.AiRefactoringSettings
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import java.time.Duration

class OpenAiCompatibleLlmClient : LlmClient {

    override fun complete(
        systemPrompt: String,
        userPrompt: String,
        settings: AiRefactoringSettings.State,
    ): String {
        if (settings.apiKey.isBlank()) throw LlmException.MissingConfiguration("API key is not set.")
        if (settings.model.isBlank()) throw LlmException.MissingConfiguration("Model is not set.")
        val trimmedBaseUrl = settings.baseUrl.trimEnd('/').ifBlank {
            throw LlmException.MissingConfiguration("Base URL is not set.")
        }
        // The SDK needs the version segment; users enter the API root (e.g. https://api.openai.com).
        val normalizedBaseUrl = if (trimmedBaseUrl.endsWith("/v1")) trimmedBaseUrl else "$trimmedBaseUrl/v1"

        val client: OpenAIClient = OpenAIOkHttpClient.builder()
            .apiKey(settings.apiKey)
            .baseUrl(normalizedBaseUrl)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(0)
            .build()

        try {
            val params = ChatCompletionCreateParams.builder()
                .model(settings.model)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                .temperature(0.0)
                .build()

            val completion: ChatCompletion = try {
                client.chat().completions().create(params)
            } catch (e: com.openai.errors.OpenAIServiceException) {
                throw LlmException.BadStatus(e.statusCode(), e.body().toString())
            } catch (e: com.openai.errors.OpenAIIoException) {
                throw LlmException.Network(e)
            } catch (e: com.openai.errors.OpenAIException) {
                throw LlmException.Network(e)
            }

            val content: String? = completion.choices().stream()
                .flatMap { choice -> choice.message().content().stream() }
                .findFirst().orElse(null)

            if (content.isNullOrEmpty()) {
                throw LlmException.MalformedResponse("LLM response had no choices/content.")
            }
            return content
        } finally {
            client.close()
        }
    }
}
