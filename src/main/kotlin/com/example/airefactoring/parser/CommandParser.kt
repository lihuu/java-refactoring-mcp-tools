package com.example.airefactoring.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class CommandParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): RefactorCommand {
        val obj = try {
            json.parseToJsonElement(raw.trim()) as? JsonObject
                ?: throw InvalidCommandException("Top-level value must be a JSON object.")
        } catch (e: InvalidCommandException) {
            throw e
        } catch (e: Exception) {
            throw InvalidCommandException("Malformed JSON: ${e.message}")
        }

        val action = obj.stringField("action")
            ?: throw InvalidCommandException("Missing or non-string action.")

        return when (action) {
            "rename_symbol" -> {
                val newName = obj.stringField("newName")
                    ?: throw InvalidCommandException("rename_symbol requires a string newName.")
                if (newName.isBlank()) throw InvalidCommandException("newName must not be blank.")
                RefactorCommand.RenameSymbol(newName.trim(), obj.stringField("reason"))
            }
            "no_action" -> RefactorCommand.NoAction(obj.stringField("reason"))
            else -> throw InvalidCommandException("Unknown action: $action")
        }
    }

    /** Returns the field's string content, or null if the field is missing or not a JSON string primitive. */
    private fun JsonObject.stringField(name: String): String? {
        val element: JsonElement = this[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull
    }
}
