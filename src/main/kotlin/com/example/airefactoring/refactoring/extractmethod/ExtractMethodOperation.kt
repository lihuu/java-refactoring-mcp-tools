package com.example.airefactoring.refactoring.extractmethod

import com.example.airefactoring.refactoring.RefactorOperation

/** The LLM asked to extract the selected code into a new method named [methodName]. */
data class ExtractMethodOperation(val methodName: String, override val reason: String?) : RefactorOperation
