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

/** 收件箱里等待发给模型的一项 */
sealed interface InboxItem {
    val id: Uuid
}

/** 用户输入：等当前这一轮结束后各自开一轮 */
data class QueuedMessage(
    override val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
    // 委托/队列跟踪（由 MessageQueue 同名字段迁移而来）
    val requestId: Uuid? = null,
    var userMessageId: Uuid? = null,
    var replyNodeId: Uuid? = null,
    // 委托结果投递：不再新增用户消息，直接在这条结果消息之后继续回复
    val deliveryMessage: UIMessage? = null,
    // 结果消息是否在投递时就已写入历史；已写入的轮次开始时不再重复追加
    var deliveryWritten: Boolean = false,
) : InboxItem {
    val deliverOnly: Boolean get() = deliveryMessage != null
}

/**
 * 运行时事件，例如后台命令结束：正在生成时在下一步请求模型前插进这一轮，空闲时单独开一轮。
 * [key] 标识事件来源，来源可以据此撤回已经不需要发送的通知。
 */
data class QueuedNotice(
    override val id: Uuid = Uuid.random(),
    val key: String,
    val notice: UIMessagePart.Notice,
) : InboxItem

data class InboxState(
    val items: List<InboxItem> = emptyList(),
    val paused: Boolean = false,
) {
    val messages: List<QueuedMessage> get() = items.filterIsInstance<QueuedMessage>()
    val notices: List<QueuedNotice> get() = items.filterIsInstance<QueuedNotice>()
}

/** 收件箱决定的下一轮 */
sealed interface InboxTurn {
    /** 发送这条用户输入；要回复时等待中的通知存成它之前的节点 */
    data class Message(val message: QueuedMessage) : InboxTurn

    /** 只有通知在等，单独开一轮；通知在开始时用 [ConversationInbox.takeNotices] 取 */
    data object Notices : InboxTurn
}

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

/**
 * 会话收件箱：等待发给模型的用户输入和运行时事件，进入会话历史之前都放在这里。
 *
 * 暂停只挡住开始新的一轮；正在进行的一轮仍然会在步骤之间取走通知。
 */
class ConversationInbox {
    private val mutableState = MutableStateFlow(InboxState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null, requestId: Uuid? = null): QueuedMessage? {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return null
        }
        val queued = QueuedMessage(
            parts = parts.toList(),
            answer = answer,
            reply = reply,
            requestId = requestId,
        )
        mutableState.value = state.value.copy(
            items = state.value.items + queued,
        )
        return queued
    }

    /** 放入一条通知；同一来源已有没发出的通知时原地替换 */
    @Synchronized
    fun post(key: String, notice: UIMessagePart.Notice) {
        val items = state.value.items
        val index = items.indexOfFirst { it is QueuedNotice && it.key == key }
        mutableState.value = state.value.copy(
            items = if (index == -1) {
                items + QueuedNotice(key = key, notice = notice)
            } else {
                items.toMutableList().apply { set(index, (get(index) as QueuedNotice).copy(notice = notice)) }
            },
        )
    }

    /** 撤回还没发出的通知；已经发出的不受影响 */
    @Synchronized
    fun withdraw(key: String) {
        mutableState.value = state.value.copy(
            items = state.value.items.filterNot { it is QueuedNotice && it.key == key },
        )
    }

    /**
     * 取出下一轮：先发用户输入，只剩通知时单独开一轮。
     * 正在编辑的队首挡住后面的用户输入，但不挡通知。
     */
    @Synchronized
    fun takeNext(): InboxTurn? {
        val current = state.value
        if (current.paused) return null
        val head = current.messages.firstOrNull()
        if (head != null && !head.isEditing) {
            mutableState.value = current.copy(items = current.items.filterNot { it.id == head.id })
            return InboxTurn.Message(head)
        }
        return InboxTurn.Notices.takeIf { current.notices.isNotEmpty() }
    }

    /** 取出全部通知，按放入顺序；不受暂停影响 */
    @Synchronized
    fun takeNotices(): List<UIMessagePart.Notice> {
        val current = state.value
        val notices = current.notices
        if (notices.isEmpty()) return emptyList()
        mutableState.value = current.copy(items = current.items.filterNot { it is QueuedNotice })
        return notices.map { it.notice }
    }

    @Synchronized
    fun remove(id: Uuid): InboxItem? {
        val removed = state.value.items.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(items = state.value.items.filterNot { it.id == id })
        (removed as? QueuedMessage)?.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            items = state.value.items.map { if (it.id == id) message.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            items = state.value.items.map {
                if (it.id == id) {
                    previous.copy(parts = parts?.toList() ?: previous.parts, isEditing = false)
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

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }

    /**
     * 排一个"投递后继续回复"的轮次（委托）：结果消息随队列项一起带到轮次开始，写进会话历史再生成，
     * 保证不会插到正在生成或等审批的那一轮中间。
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
        mutableState.value = state.value.copy(items = state.value.items + queued)
        return queued
    }

    /** 按 id 移除并让它的 reply waiter 异常结束 */
    @Synchronized
    fun removeByIdAndFail(id: Uuid, message: String): QueuedMessage? {
        val removed = state.value.items.firstOrNull { it.id == id } as? QueuedMessage ?: return null
        mutableState.value = state.value.copy(items = state.value.items.filterNot { it.id == id })
        removed.reply?.completeExceptionally(IllegalStateException(message))
        return removed
    }

    /** 让所有有 requestId 的排队项失败 */
    fun failRequestWaiters(message: String) {
        state.value.messages.filter { it.requestId != null }.forEach { removeByIdAndFail(it.id, message) }
    }
}
