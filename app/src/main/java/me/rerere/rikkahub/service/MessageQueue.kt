package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
    val requestId: Uuid? = null,
    var userMessageId: Uuid? = null,
    var replyNodeId: Uuid? = null,
    // 委托结果投递：不再新增用户消息，直接在这条结果消息之后继续回复
    val deliveryMessage: UIMessage? = null,
    // 结果消息是否在投递时就已写入历史；已写入的轮次开始时不再重复追加
    var deliveryWritten: Boolean = false,
) {
    val deliverOnly: Boolean get() = deliveryMessage != null
}

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        requestId: Uuid? = null,
        assignRequestId: Boolean = false,
    ): QueuedMessage? {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return null
        }
        val queued = QueuedMessage(
            parts = parts.toList(),
            answer = answer,
            reply = reply,
            requestId = requestId ?: if (assignRequestId) Uuid.random() else null,
        )
        mutableState.value = state.value.copy(
            messages = state.value.messages + queued,
        )
        return queued
    }

    /**
     * 排一个“投递后继续回复”的轮次：结果消息随队列项一起带到轮次开始，写进会话历史再生成，
     * 保证不会插到正在生成/等审批的那一轮中间。
     *
     * [written] 表示调用方已经趁会话空闲把结果写进了历史，轮次开始时就不必再写一遍。
     */
    @Synchronized
    fun enqueueDelivery(message: UIMessage, written: Boolean = false): QueuedMessage {
        val queued = QueuedMessage(
            parts = emptyList(),
            answer = true,
            deliveryMessage = message,
            deliveryWritten = written,
        )
        mutableState.value = state.value.copy(messages = state.value.messages + queued)
        return queued
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun removeById(id: Uuid): QueuedMessage? = remove(id)

    @Synchronized
    fun removeByIdAndFail(id: Uuid, message: String): QueuedMessage? {
        val removed = state.value.messages.firstOrNull { it.id == id } ?: return null
        mutableState.value = state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.completeExceptionally(IllegalStateException(message))
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) {
                    it.copy(
                        parts = parts?.toList() ?: it.parts,
                        isEditing = false
                    )
                } else {
                    it
                }
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    fun failRequestWaiters(message: String) {
        state.value.messages.filter { it.requestId != null }.forEach { removeByIdAndFail(it.id, message) }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }
}
