package com.example.airefactoring.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class AiRefactoringConfigurable : Configurable {

    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private val previewCheck = JBCheckBox("Show rename preview")

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Refactoring"

    override fun createComponent(): JComponent {
        val p = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Base URL:"), baseUrlField, 1, false)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField, 1, false)
            .addComponent(previewCheck, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = p
        reset()
        return p
    }

    override fun isModified(): Boolean {
        val s = AiRefactoringSettings.getInstance().state
        return baseUrlField.text != s.baseUrl ||
            String(apiKeyField.password) != s.apiKey ||
            modelField.text != s.model ||
            previewCheck.isSelected != s.enablePreview
    }

    override fun apply() {
        val s = AiRefactoringSettings.getInstance().state
        s.baseUrl = baseUrlField.text.trim()
        s.apiKey = String(apiKeyField.password).trim()
        s.model = modelField.text.trim()
        s.enablePreview = previewCheck.isSelected
    }

    override fun reset() {
        val s = AiRefactoringSettings.getInstance().state
        baseUrlField.text = s.baseUrl
        apiKeyField.text = s.apiKey
        modelField.text = s.model
        previewCheck.isSelected = s.enablePreview
    }

    override fun disposeUIResources() {
        panel = null
    }
}
