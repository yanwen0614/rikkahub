package me.rerere.rikkahub.data.ai.tools.local

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Test

class DelegateAssistantToolTest {
    @Test
    fun `delegation forbidden flag suppresses tool construction`() {
        assertEquals(false, toolIsAvailable(enabled = true, delegationAllowed = false))
        assertEquals(true, toolIsAvailable(enabled = true, delegationAllowed = true))
        assertEquals(false, toolIsAvailable(enabled = false, delegationAllowed = true))
    }

    @Test
    fun `delegate tool uses runtime name delegate to assistant`() {
        assertEquals("delegate_to_assistant", DELEGATE_ASSISTANT_TOOL_NAME)
    }

    @Test
    fun `assistant name resolution counts all exact matches before checking permissions`() {
        val source = Assistant(name = "Source")
        val allowed = Assistant(name = "Target", acceptsAssistantCalls = true)
        val denied = Assistant(name = "Target", acceptsAssistantCalls = false)

        assertFailsWith<DelegateAssistantException> {
            resolveDelegateAssistant(listOf(source, allowed, denied), source.id, "Target")
        }.also { assertEquals("ambiguous_assistant_name", it.code) }
    }

    @Test
    fun `assistant resolution distinguishes unknown and forbidden targets`() {
        val source = Assistant(name = "Source")
        assertFailsWith<DelegateAssistantException> {
            resolveDelegateAssistant(listOf(source), source.id, "Missing")
        }.also { assertEquals("unknown_assistant", it.code) }
        assertFailsWith<DelegateAssistantException> {
            resolveDelegateAssistant(listOf(source, Assistant(name = "Denied")), source.id, "Denied")
        }.also { assertEquals("assistant_not_allowed", it.code) }
    }

    @Test
    fun `approval and deletion end as explicit delegation failures`() {
        assertEquals("target_approval_required", failureCode(IllegalStateException("target_approval_required")))
        assertEquals("target_conversation_deleted", failureCode(IllegalStateException("target_conversation_deleted")))
        assertEquals("target_generation_failed", failureCode(IllegalStateException("other failure")))
    }

    @Test
    fun `delegation params enforce creation conversation rules and UUID`() {
        val conversationId = Uuid.random()
        assertEquals(true, parseDelegationParams("""{"assistant_name":"Target","message":"Hi"}""").createNew)
        assertEquals(conversationId, parseDelegationParams("""{"assistant_name":"Target","message":"Hi","create_new":false,"conversation_id":"$conversationId"}""").conversationId)
        listOf(
            """{"assistant_name":"Target","message":"Hi","conversation_id":"$conversationId"}""",
            """{"assistant_name":"Target","message":"Hi","create_new":false}""",
            """{"assistant_name":"Target","message":"Hi","conversation_id":"bad"}""",
            """{"assistant_name":"Target","message":"Hi","create_new":"false","conversation_id":"$conversationId"}""",
            """{"assistant_name":"Target","message":"Hi","conversation_id":42}""",
        ).forEach { input ->
            assertFailsWith<DelegateAssistantException> { parseDelegationParams(input) }
        }
    }
}

private fun toolIsAvailable(enabled: Boolean, delegationAllowed: Boolean): Boolean =
    enabled && delegationAllowed

private fun failureCode(error: Throwable): String {
    val supported = setOf("target_approval_required", "target_conversation_deleted", "target_generation_failed")
    return error.message?.substringBefore(':')?.takeIf { it in supported } ?: "target_generation_failed"
}
