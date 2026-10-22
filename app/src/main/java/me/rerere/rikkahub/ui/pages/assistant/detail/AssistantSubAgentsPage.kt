package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.SubAgentConfig
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun AssistantSubAgentsPage(assistantId: Uuid) {
    val vm: AssistantSubAgentsVM = koinViewModel(
        parameters = { parametersOf(assistantId) }
    )
    val subAgents by vm.subAgents.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var deleteTarget by remember { mutableStateOf<SubAgentConfig?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_sub_agents_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    navController.navigate(Screen.AssistantSubAgentDetail(assistantId.toString()))
                }
            ) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.sub_agent_add))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (subAgents.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.assistant_sub_agents_title),
                        description = stringResource(R.string.assistant_sub_agents_desc),
                        icon = HugeIcons.Bot,
                    )
                }
            } else {
                item {
                    CardGroup {
                        subAgents.forEach { subAgent ->
                            val toolCount = buildList {
                                if (subAgent.localTools != null) add("T")
                                if (subAgent.enabledSkills != null) add("S")
                                if (subAgent.mcpServers != null) add("M")
                                if (subAgent.enableWebSearch != null) add("W")
                            }.size
                            item(
                                onClick = {
                                    navController.navigate(
                                        Screen.AssistantSubAgentDetail(
                                            assistantId = assistantId.toString(),
                                            subAgentId = subAgent.id.toString(),
                                        )
                                    )
                                },
                                leadingContent = {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = subAgent.icon,
                                            modifier = Modifier.padding(8.dp),
                                            fontSize = 24.sp,
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        text = subAgent.name.ifBlank { stringResource(R.string.sub_agent_edit) },
                                        maxLines = 1,
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        if (subAgent.description.isNotBlank()) {
                                            Text(
                                                text = subAgent.description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.sub_agent_tool_count, toolCount),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                },
                                trailingContent = {
                                    ItemActionMenu(
                                        actions = listOf(
                                            ItemAction(
                                                text = stringResource(R.string.delete),
                                                icon = HugeIcons.Delete01,
                                                destructive = true,
                                                onClick = { deleteTarget = subAgent },
                                            ),
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.sub_agent_delete_confirm),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSubAgent(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(
            text = deleteTarget?.name ?: "",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

