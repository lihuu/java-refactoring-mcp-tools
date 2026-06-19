package com.example.airefactoring.llm

import com.example.airefactoring.settings.AiRefactoringSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAiCompatibleLlmClient(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun complete(
        systemPrompt: String,
        userPrompt: String,
        settings: AiRefactoringSettings.State,
    ): String {
        if (settings.apiKey.isBlank()) throw LlmException.MissingConfiguration("API key is not set.")
        if (settings.model.isBlank()) throw LlmException.MissingConfiguration("Model is not set.")
        val baseUrl = settings.baseUrl.trimEnd('/').ifBlank {
            throw LlmException.MissingConfiguration("Base URL is not set.")
        }

        val payload = ChatRequest(
            model = settings.model,
            messages = listOf(
                Message("system", systemPrompt),
                Message("user", userPrompt),
            ),
            temperature = 0.0,
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
            .build()

        val response: HttpResponse<String> = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw LlmException.Network(e)
        }

        if (response.statusCode() !in 200..299) {
            throw LlmException.BadStatus(response.statusCode(), response.body() ?: "")
        }

        val parsed = try {
            json.decodeFromString(ChatResponse.serializer(), response.body() ?: "")
        } catch (e: Exception) {
            throw LlmException.MalformedResponse("Could not parse LLM response: ${e.message}")
        }
        return parsed.choices.firstOrNull()?.message?.content
            ?: throw LlmException.MalformedResponse("LLM response had no choices/content.")
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.0,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Message? = null)
}
