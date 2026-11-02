package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 委托结果消息。
 *
 * 存储：role = SYSTEM，正文是目标助手回复（或失败的说明）的纯文本，来源信息放在
 * [UIMessagePart.Text.metadata] 里，随会话持久化。
 *
 * - UI-only：聊天页根据 metadata 渲染“来自 xxx 助手的回复”抬头（[delegationSourceName]），
 *   抬头只存在于 UI，不在正文里。
 * - model-visible：组装请求时由
 *   [me.rerere.rikkahub.data.ai.transformers.DelegationMessageTransformer] 改写成 synthetic USER，
 *   并把正文包成 `<delegation_result ...>` 提醒。多数 Provider 只取第一条 SYSTEM 作为系统提示词，
 *   夹在历史里的 SYSTEM 会被过滤掉，所以发给模型前必须换 role。
 */
internal const val DELEGATED_FROM_KEY = "delegatedFrom"
internal const val DELEGATION_CONVERSATION_ID_KEY = "delegationConversationId"
internal const val DELEGATION_FAILURE_KEY = "delegationFailure"
internal const val DELEGATION_REMINDER_KEY = "delegationReminder"

/** 目标助手失败码对应的说明文字，同时作为持久化的正文。 */
internal fun delegationFailureText(code: String): String = when (code) {
    "target_approval_required" ->
        "The target assistant paused waiting for the user to approve a tool call, so no result was produced."

    "target_conversation_deleted" ->
        "The target conversation was deleted before the delegated task finished."

    "target_generation_failed" ->
        "The target assistant failed to produce a reply."

    else -> "The delegated task failed ($code)."
}

internal fun delegationResultMessage(
    assistantName: String,
    conversationId: Uuid,
    text: String,
    failure: String? = null,
): UIMessage = UIMessage(
    role = MessageRole.SYSTEM,
    parts = listOf(
        UIMessagePart.Text(
            text = if (failure != null) delegationFailureText(failure) else text.trim(),
            metadata = buildJsonObject {
                put(DELEGATION_REMINDER_KEY, true)
                put(DELEGATED_FROM_KEY, assistantName)
                put(DELEGATION_CONVERSATION_ID_KEY, conversationId.toString())
                if (failure != null) put(DELEGATION_FAILURE_KEY, failure)
            },
        )
    ),
)

/** 发给模型的提醒正文：XML 包裹 + 给模型的说明，双方都只出现一次。 */
internal fun UIMessage.delegationReminderRequestText(): String {
    val part = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull() ?: return toText()
    val metadata = part.metadata
    val source = metadata?.string(DELEGATED_FROM_KEY).orEmpty()
    val conversationId = metadata?.string(DELEGATION_CONVERSATION_ID_KEY).orEmpty()
    val failure = metadata?.string(DELEGATION_FAILURE_KEY)
    return buildString {
        append("<delegation_result source_assistant=\"")
        append(source.escapeXmlAttribute())
        append("\" conversation_id=\"")
        append(conversationId.escapeXmlAttribute())
        append('"')
        if (failure != null) {
            append(" status=\"failed\" error=\"")
            append(failure.escapeXmlAttribute())
            append('"')
        }
        appendLine(">")
        appendLine(part.text.trim())
        appendLine("</delegation_result>")
        if (failure != null) {
            append(
                "This result came from an automated delegation. Tell the user what happened; do not retry it silently."
            )
        } else {
            append(
                "This is an automated result delivered from another assistant. Continue the conversation with the user using it."
            )
        }
    }
}

/** UI-only 抬头用的来源助手名；不是委托结果消息时返回 null。 */
internal fun UIMessage.delegationSourceName(): String? = parts.firstNotNullOfOrNull { part ->
    val metadata = (part as? UIMessagePart.Text)?.metadata ?: return@firstNotNullOfOrNull null
    if (metadata.containsKey(DELEGATION_REMINDER_KEY)) metadata.string(DELEGATED_FROM_KEY) else null
}

internal fun UIMessage.isDelegationReminderMessage(): Boolean = parts.any { part ->
    (part as? UIMessagePart.Text)?.metadata?.containsKey(DELEGATION_REMINDER_KEY) == true
}

internal fun UIMessage.delegationFailureCode(): String? = parts.firstNotNullOfOrNull { part ->
    (part as? UIMessagePart.Text)?.metadata?.string(DELEGATION_FAILURE_KEY)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun String.escapeXmlAttribute(): String = replace("&", "&amp;").replace("\"", "&quot;")
