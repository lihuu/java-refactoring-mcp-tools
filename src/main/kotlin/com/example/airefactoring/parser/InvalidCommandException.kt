package com.example.airefactoring.parser

class InvalidCommandException(message: String) : RuntimeException(message) {
    val userMessage: String = "AI response is invalid."
}
