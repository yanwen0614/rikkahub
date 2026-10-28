package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `queued reply correlation requires matching request user and assistant reply`() {
        val requestId = kotlin.uuid.Uuid.random()
        val userId = kotlin.uuid.Uuid.random()
        val userNode = UIMessage(id = userId, role = MessageRole.USER, parts = text("request")).toMessageNode()
        val replyNode = UIMessage(role = MessageRole.ASSISTANT, parts = text("answer")).toMessageNode()
        val result = ChatServiceQueuedTurnResult(requestId, userId, replyNode.id, finished = true)

        assertEquals("answer", queuedReplyText(requestId, userId, replyNode.id, result, MessageRole.USER, userId, replyNode))
        assertEquals("target_request_mismatch", runCatching {
            queuedReplyText(kotlin.uuid.Uuid.random(), userId, replyNode.id, result, MessageRole.USER, userId, replyNode)
        }.exceptionOrNull()?.message)
        assertEquals("target_user_message_mismatch", runCatching {
            queuedReplyText(requestId, userId, replyNode.id, result, MessageRole.USER, userNode.id, replyNode)
        }.exceptionOrNull()?.message)
        assertEquals("target_reply_node_mismatch", runCatching {
            queuedReplyText(requestId, userId, kotlin.uuid.Uuid.random(), result, MessageRole.USER, userId, replyNode)
        }.exceptionOrNull()?.message)
        assertEquals("target_reply_message_missing", runCatching {
            queuedReplyText(requestId, userId, replyNode.id, result.copy(finished = false), MessageRole.USER, userId, replyNode)
        }.exceptionOrNull()?.message)
        assertEquals("target_reply_message_missing", runCatching {
            queuedReplyText(
                requestId, userId, replyNode.id, result, MessageRole.USER, userId,
                MessageNode(id = replyNode.id, messages = listOf(UIMessage(role = MessageRole.USER, parts = text("wrong role")))),
            )
        }.exceptionOrNull()?.message)
    }

    @Test
    fun `assigning request ids returns unique delegation ids while normal replies stay unassigned`() {
        val queue = MessageQueue()
        val first = queue.enqueue(text("first delegation"), assignRequestId = true)
        val second = queue.enqueue(text("second delegation"), assignRequestId = true)
        val voiceReply = CompletableDeferred<String?>()
        val voice = queue.enqueue(text("voice reply"), reply = voiceReply)

        assertTrue(first?.requestId != null)
        assertTrue(second?.requestId != null)
        assertFalse(first?.requestId == second?.requestId)
        assertNull(voice?.requestId)
    }

    @Test
    fun `delegation correlation stays on the dispatched item and pending removal fails only its waiter`() = runBlocking {
        val queue = MessageQueue()
        val requestId = kotlin.uuid.Uuid.random()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("target"), reply = reply, requestId = requestId)
        val queuedId = queue.state.value.messages.single().id
        val removed = queue.removeById(queuedId)
        assertEquals(requestId, removed?.requestId)
        assertTrue(reply.isCompleted)
        assertNull(reply.getCompleted())
        assertNull(queue.takeNext())

        val startedWaiter = CompletableDeferred<String?>()
        queue.enqueue(text("started"), reply = startedWaiter, requestId = requestId)
        val started = queue.takeNext()!!
        assertEquals(requestId, started.requestId)
        assertFalse(startedWaiter.isCompleted)
    }

    @Test
    fun `approval and deletion failures are returned to delegation request`() = runBlocking {
        val queue = MessageQueue()
        val approval = CompletableDeferred<String?>()
        val deletion = CompletableDeferred<String?>()
        queue.enqueue(text("approval"), reply = approval, requestId = kotlin.uuid.Uuid.random())
        queue.enqueue(text("deleted"), reply = deletion, requestId = kotlin.uuid.Uuid.random())
        queue.failRequestWaiters("target_conversation_deleted")
        assertEquals("target_conversation_deleted", runCatching { approval.await() }.exceptionOrNull()?.message)
        assertEquals("target_conversation_deleted", runCatching { deletion.await() }.exceptionOrNull()?.message)
    }

    @Test
    fun `removing a still queued delegation by item id does not affect a started waiter`() = runBlocking {
        val queue = MessageQueue()
        val pendingReply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = pendingReply, requestId = kotlin.uuid.Uuid.random())
        val pendingId = queue.state.value.messages.single().id
        queue.removeById(pendingId)

        val startedReply = CompletableDeferred<String?>()
        queue.enqueue(text("started"), reply = startedReply, requestId = kotlin.uuid.Uuid.random())
        val started = queue.takeNext()!!
        assertTrue(startedReply.isActive)
        assertEquals(null, queue.removeById(started.id))
        assertTrue(startedReply.isActive)
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }
}
