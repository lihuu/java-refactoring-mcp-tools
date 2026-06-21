package com.example.airefactoring.refactoring.introduceparameterobject

import com.example.airefactoring.refactoring.RefactorOperation

/** The LLM asked to fold [className]'s parameters into a new parameter object named [className]. */
data class IntroduceParameterObjectOperation(val className: String, override val reason: String?) : RefactorOperation
