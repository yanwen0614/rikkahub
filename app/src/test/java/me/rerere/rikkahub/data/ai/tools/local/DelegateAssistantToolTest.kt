package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class DelegateAssistantToolTest {
    @Test
    fun `delegation forbidden flag suppresses tool construction`() {
        assertEquals(false, shouldCreateDelegateAssistantTool(enabled = true, delegationAllowed = false))
        assertEquals(true, shouldCreateDelegateAssistantTool(enabled = true, delegationAllowed = true))
        assertEquals(false, shouldCreateDelegateAssistantTool(enabled = false, delegationAllowed = true))
    }

    @Test
    fun `delegate tool uses runtime name delegate to assistant`() {
        assertEquals("delegate_to_assistant", DELEGATE_ASSISTANT_TOOL_NAME)
    }

    @Test
    fun `available assistants listing includes delegation descriptions`() {
        val assistants = listOf(
            Assistant(name = "foo", acceptsAssistantCalls = true, delegationDescription = "擅长翻译"),
            Assistant(name = "bar", acceptsAssistantCalls = true, delegationDescription = "   "),
            Assistant(name = "baz", acceptsAssistantCalls = true),
        )

        assertEquals(
            "- foo: 擅长翻译\n- bar\n- baz",
            formatDelegateAssistantList(assistants),
        )
        val toolDescription = buildDelegateAssistantDescription(assistants)
        assertEquals(true, toolDescription.contains("- foo: 擅长翻译"))
        assertEquals(true, toolDescription.contains("- bar"))
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
    fun `execution revalidation rejects renamed ambiguous or disabled target`() {
        val expected = Assistant(name = "Original", acceptsAssistantCalls = true)
        assertFailsWith<DelegateAssistantException> {
            revalidateDelegateAssistant(listOf(expected.copy(name = "Renamed")), expected.id, "Original")
        }.also { assertEquals("unknown_assistant", it.code) }
        assertFailsWith<DelegateAssistantException> {
            revalidateDelegateAssistant(
                listOf(expected, Assistant(name = "Original", acceptsAssistantCalls = true)),
                expected.id,
                "Original",
            )
        }.also { assertEquals("ambiguous_assistant_name", it.code) }
        assertFailsWith<DelegateAssistantException> {
            revalidateDelegateAssistant(listOf(expected.copy(acceptsAssistantCalls = false)), expected.id, "Original")
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
        assertEquals(
            conversationId,
            parseDelegationParams(
                """{"assistant_name":"Target","message":"Hi","create_new":false,"conversation_id":"$conversationId"}"""
            ).conversationId
        )
        listOf(
            """{"assistant_name":"Target","message":"Hi","conversation_id":"$conversationId"}""",
            """{"assistant_name":"Target","message":"Hi","create_new":false}""",
            """{"assistant_name":"Target","message":"Hi","conversation_id":"bad"}""",
            """{"assistant_name":"Target","message":"Hi","create_new":"false","conversation_id":"$conversationId"}""",
            """{"assistant_name":"Target","message":"Hi","conversation_id":42}""",
            """{"assistant_name":42,"message":"Hi"}""",
            """{"assistant_name":"Target","message":false}""",
        ).forEach { input ->
            assertFailsWith<DelegateAssistantException> { parseDelegationParams(input) }
        }
    }
}

private fun failureCode(error: Throwable): String {
    val supported = setOf("target_approval_required", "target_conversation_deleted", "target_generation_failed")
    return error.message?.substringBefore(':')?.takeIf { it in supported } ?: "target_generation_failed"
}
