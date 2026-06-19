package com.example.airefactoring.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "AiRefactoringSettings", storages = [Storage("aiRefactoring.xml")])
class AiRefactoringSettings : PersistentStateComponent<AiRefactoringSettings.State> {
    data class State(
        var baseUrl: String = "https://api.openai.com",
        var apiKey: String = "",
        var model: String = "gpt-4o-mini",
        var enablePreview: Boolean = true,
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    companion object {
        fun getInstance(): AiRefactoringSettings =
            ApplicationManager.getApplication().getService(AiRefactoringSettings::class.java)
    }
}
