package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.DELEGATED_FROM_KEY
import me.rerere.rikkahub.data.model.DELEGATION_CONVERSATION_ID_KEY
import me.rerere.rikkahub.data.model.DELEGATION_FAILURE_KEY
import me.rerere.rikkahub.data.model.DELEGATION_REMINDER_KEY
import me.rerere.rikkahub.data.model.delegationFailureCode
import me.rerere.rikkahub.data.model.delegationResultMessage
import me.rerere.rikkahub.data.model.delegationSourceName
import me.rerere.rikkahub.data.model.isDelegationReminderMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DelegationMessageTransformerTest {
    private val conversationId = Uuid.random()

    private fun transform(messages: List<UIMessage>): List<UIMessage> =
        applyDelegationMessageTransform(messages)

    @Test
    fun `result message is stored as system with ui only source metadata`() {
        val message = delegationResultMessage("Target", conversationId, "the answer")
        assertEquals(MessageRole.SYSTEM, message.role)
        assertEquals("Target", message.delegationSourceName())
        assertTrue(message.isDelegationReminderMessage())
        assertNull(message.delegationFailureCode())
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals("the answer", text)
        assertFalse(text.contains("delegatedFrom"))
    }

    @Test
    fun `failure code is persisted in metadata and readable for the ui`() {
        val message = delegationResultMessage(
            "Target",
            conversationId,
            text = "",
            failure = "target_approval_required",
        )
        assertEquals("target_approval_required", message.delegationFailureCode())
        assertTrue(message.parts.filterIsInstance<UIMessagePart.Text>().single().text.contains("approve"))
    }

    @Test
    fun `request rewrite turns the stored system message into a synthetic user reminder`() {
        val message = delegationResultMessage("Target", conversationId, "the answer")
        val result = transform(listOf(message)).single()

        assertEquals(MessageRole.USER, result.role)
        assertTrue(result.isSynthetic)
        val text = result.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.startsWith("<delegation_result source_assistant=\"Target\""))
        assertTrue(text.contains(conversationId.toString()))
        assertTrue(text.contains("the answer"))
        assertTrue(
            text.endsWith(
                "</delegation_result>\nThis is an automated result delivered from another assistant. Continue the conversation with the user using it."
            )
        )
    }

    @Test
    fun `failure reminder tells the model the task failed`() {
        val message = delegationResultMessage("Target", conversationId, "", failure = "target_generation_failed")
        val text = transform(listOf(message)).single()
            .parts.filterIsInstance<UIMessagePart.Text>().single().text

        assertTrue(text.contains("status=\"failed\""))
        assertTrue(text.contains("error=\"target_generation_failed\""))
        assertTrue(text.contains("do not retry it silently"))
    }

    @Test
    fun `metadata survives json round trip`() {
        val json = Json
        val message = delegationResultMessage("Target", conversationId, "the answer")
        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<UIMessage>(encoded)

        assertEquals("Target", decoded.delegationSourceName())
        val metadata = decoded.parts.filterIsInstance<UIMessagePart.Text>().single().metadata!!.jsonObject
        assertEquals(JsonPrimitive(conversationId.toString()), metadata[DELEGATION_CONVERSATION_ID_KEY])
        assertEquals(JsonPrimitive(true), metadata[DELEGATION_REMINDER_KEY])
        assertTrue(metadata.containsKey(DELEGATED_FROM_KEY))
        assertFalse(metadata.containsKey(DELEGATION_FAILURE_KEY))
    }

    @Test
    fun `ordinary messages pass through untouched`() {
        val user = UIMessage.user("hello")
        val assistant = UIMessage.assistant("hi")
        val result = transform(listOf(user, assistant))
        assertEquals(listOf(user, assistant), result)
    }

    // ---- 第二步：并入最近一条用户消息 ----

    @Test
    fun `reminder is appended to the end of the latest user message`() {
        val user = UIMessage.user("please book a table")
        val assistant = UIMessage.assistant("working on it")
        val reminder = delegationResultMessage("Target", conversationId, "table booked")
        val messages = transform(listOf(user, assistant, reminder))

        val merged = mergeDelegationReminders(messages)
        // 只剩两条：提醒不再是独立消息
        assertEquals(2, merged.size)
        assertEquals(MessageRole.USER, merged[0].role)
        assertEquals(MessageRole.ASSISTANT, merged[1].role)

        val userTexts = merged[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals("please book a table", userTexts.first())
        // 提醒接在用户消息末尾，正文仍是 XML 包裹
        assertTrue(userTexts.last().startsWith("<delegation_result"))
        assertTrue(userTexts.last().contains("table booked"))
    }

    @Test
    fun `reminder in a fresh conversation falls back to a standalone user message`() {
        val reminder = delegationResultMessage("Target", conversationId, "the answer")
        val merged = mergeDelegationReminders(transform(listOf(reminder)))

        assertEquals(1, merged.size)
        assertEquals(MessageRole.USER, merged[0].role)
        assertTrue(merged[0].isSynthetic)
        assertTrue(
            merged[0].parts.filterIsInstance<UIMessagePart.Text>().single().text.startsWith("<delegation_result")
        )
    }

    @Test
    fun `multiple reminders in one request all land on the same user anchor`() {
        val user = UIMessage.user("run both")
        val first = delegationResultMessage("A", conversationId, "a done")
        val second = delegationResultMessage("B", Uuid.random(), "b done")
        val merged = mergeDelegationReminders(transform(listOf(user, first, second)))

        assertEquals(1, merged.size)
        val texts = merged[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals("run both", texts.first())
        assertEquals(2, texts.count { it.startsWith("<delegation_result") })
    }

    @Test
    fun `reminder merges into the user message immediately before it, not an earlier one`() {
        val earlierUser = UIMessage.user("first question")
        val earlierAssistant = UIMessage.assistant("first answer")
        val laterUser = UIMessage.user("second question")
        val laterAssistant = UIMessage.assistant("second answer")
        val reminder = delegationResultMessage("Target", conversationId, "late result")

        val merged = mergeDelegationReminders(
            transform(listOf(earlierUser, earlierAssistant, laterUser, laterAssistant, reminder))
        )

        assertEquals(4, merged.size)
        val earlierTexts = merged[0].parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        val laterTexts = merged[2].parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals(listOf("first question"), earlierTexts)
        assertTrue(laterTexts.last().startsWith("<delegation_result"))
    }

    @Test
    fun `merge leaves requests without reminders completely untouched`() {
        val user = UIMessage.user("hello")
        val assistant = UIMessage.assistant("hi")
        val messages = listOf(user, assistant)

        assertSame(messages, mergeDelegationReminders(messages))
    }
}
