package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SubAgentConfig
import me.rerere.rikkahub.data.model.SubAgentWorkspaceMode
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.EmojiPicker
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.SelectTextField
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun SubAgentDetailPage(
    assistantId: Uuid,
    subAgentId: Uuid?,
) {
    val vm: AssistantSubAgentsVM = koinViewModel(
        parameters = { parametersOf(assistantId) }
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val subAgents by vm.subAgents.collectAsStateWithLifecycle()

    val parentAssistant = remember(settings.assistants, assistantId) {
        settings.assistants.find { it.id == assistantId }
    }
    val existing = remember(subAgents, subAgentId) {
        subAgentId?.let { id -> subAgents.find { it.id == id } }
    }

    var config by remember(subAgentId, subAgents) {
        mutableStateOf(existing ?: SubAgentConfig())
    }

    val providers = settings.providers
    val modelListState = rememberModelListState(
        modelId = config.chatModelId,
        providers = providers,
        type = ModelType.CHAT,
    )
    ModelListSheet(
        state = modelListState,
        onSelect = { model ->
            config = config.copy(chatModelId = model.id)
        },
    )

    // Auto-save on every config change
    androidx.compose.runtime.LaunchedEffect(config, subAgents) {
        val list = subAgents.toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            list[idx] = config
        } else {
            list.add(config)
        }
        vm.saveSubAgents(list)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        if (subAgentId == null) {
                            stringResource(R.string.sub_agent_add)
                        } else {
                            stringResource(R.string.sub_agent_edit)
                        }
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Section 1: Basic Info ──
            CardGroup(title = { Text(stringResource(R.string.sub_agent_basic_info)) }) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.sub_agent_icon)) },
                ) {
                    IconPickerRow(
                        currentIcon = config.icon,
                        onIconSelected = { config = config.copy(icon = it) },
                    )
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.sub_agent_name)) },
                ) {
                    OutlinedTextField(
                        value = config.name,
                        onValueChange = { config = config.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.sub_agent_description)) },
                ) {
                    OutlinedTextField(
                        value = config.description,
                        onValueChange = { config = config.copy(description = it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                formItem(
                    headlineContent = { Text(stringResource(R.string.sub_agent_system_prompt)) },
                    supportingContent = { Text(stringResource(R.string.sub_agent_system_prompt_hint)) },
                ) {
                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = { config = config.copy(systemPrompt = it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                }
            }

            // ── Section 2: Model & Params ──
            CardGroup(title = { Text(stringResource(R.string.sub_agent_model_params)) }) {
                // Chat Model
                val inheritModel = config.chatModelId == null
                formItem(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_chat_model),
                            inherited = inheritModel,
                        )
                    },
                    trailingContent = {
                        Switch(checked = inheritModel, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(chatModelId = null)
                            } else {
                                config.copy(chatModelId = parentAssistant?.chatModelId)
                            }
                        })
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritModel)
                    },
                ) {
                    AnimatedVisibility(visible = !inheritModel) {
                        ModelField(
                            model = modelListState.currentModel,
                            onClick = { modelListState.open() },
                        )
                    }
                }

                // Temperature
                val inheritTemp = config.temperature == null
                formItem(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_temperature),
                            inherited = inheritTemp,
                        )
                    },
                    trailingContent = {
                        Switch(checked = inheritTemp, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(temperature = null)
                            } else {
                                config.copy(temperature = parentAssistant?.temperature ?: 1.0f)
                            }
                        })
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritTemp)
                    },
                ) {
                    AnimatedVisibility(visible = !inheritTemp) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Slider(
                                value = config.temperature ?: 1.0f,
                                onValueChange = { config = config.copy(temperature = it) },
                                valueRange = 0f..2f,
                                steps = 39,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "%.2f".format(config.temperature ?: 1.0f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Top P
                val inheritTopP = config.topP == null
                formItem(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_top_p),
                            inherited = inheritTopP,
                        )
                    },
                    trailingContent = {
                        Switch(checked = inheritTopP, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(topP = null)
                            } else {
                                config.copy(topP = parentAssistant?.topP ?: 1.0f)
                            }
                        })
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritTopP)
                    },
                ) {
                    AnimatedVisibility(visible = !inheritTopP) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Slider(
                                value = config.topP ?: 1.0f,
                                onValueChange = { config = config.copy(topP = it) },
                                valueRange = 0f..1f,
                                steps = 19,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "%.2f".format(config.topP ?: 1.0f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Max Tokens
                val inheritTokens = config.maxTokens == null
                formItem(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_max_tokens),
                            inherited = inheritTokens,
                        )
                    },
                    trailingContent = {
                        Switch(checked = inheritTokens, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(maxTokens = null)
                            } else {
                                config.copy(maxTokens = parentAssistant?.maxTokens ?: 4096)
                            }
                        })
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritTokens)
                    },
                ) {
                    AnimatedVisibility(visible = !inheritTokens) {
                        OutlinedTextField(
                            value = config.maxTokens?.toString() ?: "",
                            onValueChange = { text ->
                                val tokens = text.toIntOrNull()?.takeIf { it > 0 }
                                config = config.copy(maxTokens = tokens)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.assistant_page_max_tokens_no_limit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }

                // Reasoning Level
                val inheritReasoning = config.reasoningLevel == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_thinking_budget),
                            inherited = inheritReasoning,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritReasoning)
                    },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = config.reasoningLevel ?: ReasoningLevel.AUTO,
                            onlyIcon = true,
                            onUpdateReasoningLevel = { level ->
                                if (level == (parentAssistant?.reasoningLevel ?: ReasoningLevel.AUTO)) {
                                    config = config.copy(reasoningLevel = null)
                                } else {
                                    config = config.copy(reasoningLevel = level)
                                }
                            },
                        )
                    },
                    supportingContent = {
                        if (inheritReasoning) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                // Context Message Limit
                val inheritCtxLimit = config.contextMessageLimit == null
                formItem(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_context_message_limit),
                            inherited = inheritCtxLimit,
                        )
                    },
                    trailingContent = {
                        Switch(checked = inheritCtxLimit, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(contextMessageLimit = null)
                            } else {
                                config.copy(contextMessageLimit = parentAssistant?.contextMessageLimit ?: 0)
                            }
                        })
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritCtxLimit)
                    },
                ) {
                    AnimatedVisibility(visible = !inheritCtxLimit) {
                        OutlinedTextField(
                            value = config.contextMessageLimit?.toString() ?: "",
                            onValueChange = { text ->
                                val limit = text.toIntOrNull()?.coerceAtLeast(0)
                                config = config.copy(contextMessageLimit = limit)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                }
            }

            // ── Section 3: Tool Permissions ──
            CardGroup(title = { Text(stringResource(R.string.sub_agent_tool_permissions)) }) {
                val inheritLocalTools = config.localTools == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_tab_local_tools),
                            inherited = inheritLocalTools,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritLocalTools)
                    },
                    trailingContent = {
                        Switch(checked = inheritLocalTools, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(localTools = null)
                            } else {
                                config.copy(localTools = parentAssistant?.localTools ?: emptyList())
                            }
                        })
                    },
                    supportingContent = {
                        if (inheritLocalTools) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                val inheritSkills = config.enabledSkills == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.extensions_page_agent_skills),
                            inherited = inheritSkills,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritSkills)
                    },
                    trailingContent = {
                        Switch(checked = inheritSkills, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(enabledSkills = null)
                            } else {
                                config.copy(enabledSkills = parentAssistant?.enabledSkills ?: emptySet())
                            }
                        })
                    },
                    supportingContent = {
                        if (inheritSkills) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                val inheritMcp = config.mcpServers == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_tab_mcp),
                            inherited = inheritMcp,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritMcp)
                    },
                    trailingContent = {
                        Switch(checked = inheritMcp, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(mcpServers = null)
                            } else {
                                config.copy(mcpServers = parentAssistant?.mcpServers ?: emptySet())
                            }
                        })
                    },
                    supportingContent = {
                        if (inheritMcp) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                val inheritWebSearch = config.enableWebSearch == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.use_web_search),
                            inherited = inheritWebSearch,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritWebSearch)
                    },
                    trailingContent = {
                        Switch(checked = inheritWebSearch, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(enableWebSearch = null)
                            } else {
                                config.copy(enableWebSearch = parentAssistant?.enableWebSearch ?: false)
                            }
                        })
                    },
                    supportingContent = {
                        if (inheritWebSearch) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                val inheritMemory = config.enableMemory == null
                item(
                    headlineContent = {
                        InheritLabel(
                            label = stringResource(R.string.assistant_page_memory),
                            inherited = inheritMemory,
                        )
                    },
                    leadingContent = {
                        InheritIcon(inherited = inheritMemory)
                    },
                    trailingContent = {
                        Switch(checked = inheritMemory, onCheckedChange = { inherit ->
                            config = if (inherit) {
                                config.copy(enableMemory = null)
                            } else {
                                config.copy(enableMemory = parentAssistant?.enableMemory ?: false)
                            }
                        })
                    },
                    supportingContent = {
                        if (inheritMemory) {
                            Text(stringResource(R.string.sub_agent_inherit_parent))
                        }
                    },
                )

                // Workspace Mode
                formItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_workspace)) },
                ) {
                    SelectTextField(
                        value = when (config.workspaceMode) {
                            SubAgentWorkspaceMode.INHERIT -> stringResource(R.string.sub_agent_workspace_inherit)
                            SubAgentWorkspaceMode.NONE -> stringResource(R.string.sub_agent_workspace_none)
                            SubAgentWorkspaceMode.SPECIFIC -> stringResource(R.string.sub_agent_workspace_specific)
                        },
                        options = SubAgentWorkspaceMode.entries,
                        readOnly = true,
                        onOptionSelected = { mode ->
                            config = config.copy(
                                workspaceMode = mode,
                                workspaceId = if (mode == SubAgentWorkspaceMode.SPECIFIC) {
                                    config.workspaceId ?: Uuid.random()
                                } else {
                                    null
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        optionToString = { mode ->
                            when (mode) {
                                SubAgentWorkspaceMode.INHERIT -> stringResource(R.string.sub_agent_workspace_inherit)
                                SubAgentWorkspaceMode.NONE -> stringResource(R.string.sub_agent_workspace_none)
                                SubAgentWorkspaceMode.SPECIFIC -> stringResource(R.string.sub_agent_workspace_specific)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InheritLabel(label: String, inherited: Boolean) {
    Column {
        Text(text = label)
        if (inherited) {
            Text(
                text = stringResource(R.string.sub_agent_inherit_parent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InheritIcon(inherited: Boolean) {
    CardGroupIcon(
        icon = if (inherited) HugeIcons.Link01 else HugeIcons.PencilEdit01,
        containerColor = if (inherited) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (inherited) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
    )
}

@Composable
private fun IconPickerRow(
    currentIcon: String,
    onIconSelected: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = currentIcon,
                    fontSize = 28.sp,
                )
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.sub_agent_icon),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                EmojiPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    onEmojiSelected = { emoji ->
                        onIconSelected(emoji.emoji)
                        showPicker = false
                    },
                )
            }
        }
    }
}
