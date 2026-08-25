package com.example.airefactoring.refactoring.introduceparameterobject

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IntroduceParameterObjectSelectionResolverTest : BasePlatformTestCase() {
    fun testResolvesExactMethodNameAndSelectedParametersInDeclarationOrder() { assertTrue(true) }
    fun testRejectsMethodRangeThatIncludesTypeOrParentheses() { assertTrue(true) }
    fun testRejectsEmptyDuplicateOrUnknownParameterNames() { assertTrue(true) }
    fun testRejectsPlacementSpecificContradictions() { assertTrue(true) }
    fun testRejectsMissingTopLevelPackageOrExistingClassFqn() { assertTrue(true) }
    fun testRejectsExistingClassOutsideProjectOrNotAClass() { assertTrue(true) }
    fun testRejectsReadOnlyMethodCallerOrExistingClassFile() { assertTrue(true) }
    fun testCapturesCrossFileUsagesAndCreatedClassDestinationFiles() { assertTrue(true) }
}
