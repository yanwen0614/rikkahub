package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SubAgentRequest
import me.rerere.rikkahub.data.ai.SubAgentResult
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.SubAgentToolMetadata
import me.rerere.rikkahub.data.ai.SubAgentToolPayload
import me.rerere.rikkahub.data.ai.toJsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.escapeXml
import kotlin.uuid.Uuid

private const val MAX_SUB_AGENT_RESULT_CHARS = 8000

fun createSubAgentTool(
    assistant: Assistant,
    conversationId: Uuid,
    runner: SubAgentRunner,
): Tool = Tool(
    name = "call_sub_agent",
    description = "Delegate a task to a specialized sub-agent. " +
        "The sub-agent has its own system prompt, model, and tools. " +
        "Use sub_session_id to continue an existing sub-session.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "The name of the sub-agent to call")
                })
                put("task", buildJsonObject {
                    put("type", "string")
                    put("description", "The full task description to delegate")
                })
                put("sub_session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional. Continue an existing sub-session by its ID")
                })
            },
            required = listOf("name", "task")
        )
    },
    systemPrompt = { _, _ ->
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("**Available Sub-Agents**")
        sb.appendLine("You can delegate a task with the `call_sub_agent` tool.")
        sb.appendLine("Use `sub_session_id` only when continuing an existing sub-session.")
        sb.appendLine("<available_sub_agents>")
        for (sub in assistant.subAgents) {
            sb.appendLine("  <sub_agent>")
            sb.appendLine("    <name>${sub.name.escapeXml()}</name>")
            sb.appendLine("    <icon>${sub.icon}</icon>")
            sb.appendLine(
                "    <description>${(sub.description.ifBlank { sub.systemPrompt.take(200) }).escapeXml()}</description>"
            )
            sb.appendLine("  </sub_agent>")
        }
        sb.appendLine("</available_sub_agents>")
        sb.toString()
    },
    execute = execute@{ args ->
        val name = args.jsonObject["name"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return@execute listOf(errorPayload("name is required"))
        val task = args.jsonObject["task"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return@execute listOf(errorPayload("task is required"))
        val rawSubSessionId = args.jsonObject["sub_session_id"]
            ?.jsonPrimitive?.contentOrNull
        val subSessionId = rawSubSessionId?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        if (!rawSubSessionId.isNullOrBlank() && subSessionId == null) {
            return@execute listOf(errorPayload("invalid sub_session_id"))
        }

        val result = try {
            runner.run(
                SubAgentRequest(
                    parentConversationId = conversationId,
                    subAgentName = name,
                    task = task,
                    subSessionId = subSessionId,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@execute listOf(errorPayload("Sub-agent failed: ${e.message}"))
        }

        val payload = SubAgentToolPayload(
            status = result.status.name.lowercase(),
            sub_session_id = result.subSessionId?.toString().orEmpty(),
            summary = result.summary.take(MAX_SUB_AGENT_RESULT_CHARS),
        )
        listOf(
            UIMessagePart.Text(
                text = JsonInstant.encodeToString(payload),
                metadata = SubAgentToolMetadata(
                    subSessionId = result.subSessionId?.toString(),
                    name = result.name,
                    icon = result.icon,
                    durationMs = result.durationMs,
                ).toJsonObject(),
            )
        )
    },
)

private fun errorPayload(message: String): UIMessagePart.Text {
    val payload = SubAgentToolPayload(
        status = "failed",
        sub_session_id = "",
        summary = message,
    )
    return UIMessagePart.Text(JsonInstant.encodeToString(payload))
}
