package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SubAgentConfig
import kotlin.uuid.Uuid

class AssistantSubAgentsVM(
    private val assistantId: Uuid,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val subAgents: StateFlow<List<SubAgentConfig>> = settingsStore.settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId }?.subAgents ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveSubAgents(subAgents: List<SubAgentConfig>) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(subAgents = subAgents)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
    }

    fun deleteSubAgent(id: Uuid) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id == assistantId) {
                            assistant.copy(
                                subAgents = assistant.subAgents.filter { it.id != id }
                            )
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
    }
}
