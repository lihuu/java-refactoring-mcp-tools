package com.example.airefactoring.llm

sealed class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class MissingConfiguration(message: String) : LlmException(message)
    class Network(cause: Throwable) : LlmException("LLM network error: ${cause.message}", cause)
    class BadStatus(val code: Int, val body: String) : LlmException("LLM returned HTTP $code")
    class MalformedResponse(message: String) : LlmException(message)
}
