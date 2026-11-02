package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.delegationReminderRequestText
import me.rerere.rikkahub.data.model.isDelegationReminderMessage

/**
 * 委托结果消息进入请求前的第一步处理（转换链最前）。
 *
 * 结果消息以 SYSTEM 存在会话里，但多数 Provider 只取第一条 SYSTEM 作为系统提示词，
 * 夹在历史里的 SYSTEM 会被过滤掉（Claude / Gemini / Responses API），所以不能原样发出去。
 * 这里先把它改写成 synthetic USER：
 * - 有些 Provider 只接受两种 role，SYSTEM 必须换掉；
 * - 标成 synthetic 是为了让后面的 [TemplateTransformer] 跳过它，
 *   否则正文会被助手的 messageTemplate（用户可自定义）二次包装。
 *
 * 真正的“并入用户消息”在链尾的 [DelegationReminderMergeTransformer] 做，
 * 那时模板/占位符已经处理完，提醒不会被改写。
 */
object DelegationMessageTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = applyDelegationMessageTransform(messages)
}

internal fun applyDelegationMessageTransform(messages: List<UIMessage>): List<UIMessage> =
    messages.map { message ->
        if (!message.isDelegationReminderMessage()) return@map message
        message.copy(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = message.delegationReminderRequestText(),
                    // 保留来源 metadata：链尾的合并步骤还要靠它认出这条提醒，
                    // 合并进用户消息时不会把这段 metadata 带进正文
                    metadata = message.parts.firstNotNullOfOrNull { it.metadata },
                )
            ),
            isSynthetic = true,
        )
    }

/**
 * 委托结果消息进入请求前的第二步处理（转换链最后）。
 *
 * 把委托提醒接进**最近一条用户消息的末尾**，而不是留成单独一条消息：
 * 委托结果本来就发生在某轮用户输入之后，模型正在为那条输入继续说话，
 * 挂在同一个 user 块里读起来就是“这条消息的结果刚好回来了”，
 * 也不会出现连续的 user 消息（部分 Provider 对相邻同角色消息处理不一致）。
 *
 * 会话里委托结果之前没有任何用户消息时（例如目标会话的第一次委托）退化为单独一条
 * synthetic USER，保持原位置。
 */
object DelegationReminderMergeTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = mergeDelegationReminders(messages)
}

internal fun mergeDelegationReminders(messages: List<UIMessage>): List<UIMessage> {
    val reminderIndices = messages.indices.filter { messages[it].isDelegationReminderMessage() }
    if (reminderIndices.isEmpty()) return messages
    val reminderIndexSet = reminderIndices.toSet()

    // 先收集全部提醒，再为每条找锚点：锚点是它之前最近的一条非委托 USER 消息。
    // 委托之间可以互相夹着 assistant 消息，所以不能只看“前一条”。
    val appended = LinkedHashMap<Int, MutableList<String>>()
    val standalone = LinkedHashMap<Int, String>()

    reminderIndices.forEach { index ->
        val text = messages[index]
            .parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n\n") { it.text }
        val anchorIndex = (index - 1 downTo 0).firstOrNull { candidate ->
            candidate !in reminderIndexSet && messages[candidate].role == MessageRole.USER
        }
        if (anchorIndex == null) {
            standalone[index] = text
        } else {
            appended.getOrPut(anchorIndex) { mutableListOf() } += text
        }
    }

    val result = mutableListOf<UIMessage>()
    messages.forEachIndexed { index, message ->
        standalone[index]?.let { text ->
            result += UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(text)),
                isSynthetic = true,
            )
            return@forEachIndexed
        }
        if (index in reminderIndexSet) return@forEachIndexed

        val attached = appended[index]
        result += if (attached.isNullOrEmpty()) {
            message
        } else {
            message.copy(parts = message.parts + attached.map { UIMessagePart.Text(it) })
        }
    }
    return result
}
