package me.rerere.rikkahub.data.ai.tools.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant

class DelegateAssistantToolTest {
    @Test
    fun `delegation forbidden flag suppresses tool construction`() {
        assertEquals(false, shouldCreateDelegateAssistantTool(enabled = true, delegationAllowed = false))
        assertEquals(true, shouldCreateDelegateAssistantTool(enabled = true, delegationAllowed = true))
        assertEquals(false, shouldCreateDelegateAssistantTool(enabled = false, delegationAllowed = true))
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
        assertEquals("target_approval_required", delegationFailure(IllegalStateException("target_approval_required")).code)
        assertEquals("target_conversation_deleted", delegationFailure(IllegalStateException("target_conversation_deleted")).code)
        assertEquals("target_generation_failed", delegationFailure(IllegalStateException("other failure")).code)
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
        ).forEach { input ->
            assertFailsWith<DelegateAssistantException> { parseDelegationParams(input) }
        }
    }
}
