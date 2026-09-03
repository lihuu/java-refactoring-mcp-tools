package com.example.airefactoring.refactoring.introducemember

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.refactoring.introduceField.BaseExpressionToFieldHandler

/**
 * Builds the native [BaseExpressionToFieldHandler.Settings] used by the headless Introduce
 * Constant / Introduce Field executors, with every UI choice fixed to the headless profile:
 * replace-all off, initialize in field declaration, private visibility, no local variable
 * rewrite, no NonNls annotation, no enum-constant mode.
 *
 * The 14-argument `Settings` constructor exists on both supported platforms (builds 261 and
 * 262), but its seventh parameter type moved in 262 from the nested
 * `BaseExpressionToFieldHandler.InitializationPlace` to
 * `JavaIntroduceFieldService.InitializationPlace`, and the old nested enum was removed. The
 * two builds share no supertype for that parameter, so the constructor is located reflectively
 * and the initialization place is resolved by enum-constant name from the type the resolved
 * constructor actually declares. Every other argument keeps its ordinary compile-time type,
 * which is identical across both builds, so a single plugin binary runs unchanged on 261 and 262.
 */
internal object HeadlessIntroduceMemberSettings {

    private const val INITIALIZATION_PLACE_CONSTANT = "IN_FIELD_DECLARATION"

    fun build(
        fieldName: String,
        selectedExpression: PsiExpression,
        declareStatic: Boolean,
        forcedType: PsiType?,
        targetClass: PsiClass,
    ): BaseExpressionToFieldHandler.Settings {
        val constructor = BaseExpressionToFieldHandler.Settings::class.java.declaredConstructors
            .filter {
                it.parameterCount == 14 &&
                    it.parameterTypes[6].isEnum &&
                    it.parameterTypes[11] == BaseExpressionToFieldHandler.TargetDestination::class.java
            }
            .singleOrNull() ?: throw IntroduceMemberPreparationException(
            "The native Introduce Field settings constructor expected on this IDE build was not found.",
        )
        val initializationPlace = constructor.parameterTypes[6].enumConstants
            ?.filterIsInstance<Enum<*>>()
            ?.singleOrNull { it.name == INITIALIZATION_PLACE_CONSTANT }
            ?: throw IntroduceMemberPreparationException(
                "The native initialization place \"$INITIALIZATION_PLACE_CONSTANT\" " +
                    "expected on this IDE build was not found.",
            )
        constructor.isAccessible = true
        return constructor.newInstance(
            fieldName,
            selectedExpression,
            arrayOf(selectedExpression),
            false,
            declareStatic,
            true,
            initializationPlace,
            PsiModifier.PRIVATE,
            null,
            forcedType,
            false,
            BaseExpressionToFieldHandler.TargetDestination(targetClass),
            false,
            false,
        ) as BaseExpressionToFieldHandler.Settings
    }
}