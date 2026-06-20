package com.example.airefactoring.refactoring

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Returns the field's string content, or null if missing or not a JSON string primitive. */
internal fun JsonObject.stringField(name: String): String? {
    val element: JsonElement = this[name] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}
