package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.SubAgentToolPayload
import me.rerere.rikkahub.data.ai.subAgentMetadata
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.JsonInstant

object CallSubAgentToolUI : ToolUIRenderer {
    override val toolName = "call_sub_agent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val meta = context.tool.output.firstOrNull()?.subAgentMetadata()
        val name = meta?.name
            ?: context.arguments.getStringContent("name")
            ?: stringResource(R.string.tool_call_sub_agent_title)
        val icon = meta?.icon ?: "\uD83E\uDD16"
        return "$icon $name"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val payload = context.content?.let {
            runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolPayload>(it) }.getOrNull()
        }
        val text = when (payload?.status) {
            "running" -> stringResource(R.string.sub_agent_running)
            "completed" -> payload.summary.take(100)
            "cancelled" -> stringResource(R.string.sub_agent_cancelled)
            "failed" -> payload.summary.take(100)
            else -> payload?.summary?.take(100) ?: stringResource(R.string.sub_agent_running)
        }
        Text(
            text = text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val payload = context.content?.let {
            runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolPayload>(it) }.getOrNull()
        } ?: return
        val meta = context.tool.output.firstOrNull()?.subAgentMetadata()
        val navController = LocalNavController.current

        Column {
            Text(text = "Status: ${payload.status}")
            if (payload.summary.isNotBlank()) {
                Text(
                    text = payload.summary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.durationMs?.let { ms ->
                Text(text = "Duration: ${ms}ms")
            }
            if (payload.sub_session_id.isNotBlank()) {
                Button(
                    onClick = {
                        onDismissRequest()
                        navController.navigate(Screen.Chat(id = payload.sub_session_id))
                    }
                ) {
                    Text(stringResource(R.string.sub_agent_open_session))
                }
            }
        }
    }
}
