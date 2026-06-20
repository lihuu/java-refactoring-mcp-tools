package com.example.airefactoring.refactoring

/**
 * A handler's contribution to the LLM prompt. [systemFragment] states the refactoring's rules;
 * [jsonShapeExample] is the exact JSON shape the model should return for this refactoring
 * (its "action" value must equal the handler's id).
 */
data class PromptContribution(
    val systemFragment: String,
    val jsonShapeExample: String,
)
