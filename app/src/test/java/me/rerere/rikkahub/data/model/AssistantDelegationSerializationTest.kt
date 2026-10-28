package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDelegationSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `legacy assistant defaults accepting calls to false`() {
        val assistant = json.decodeFromString<Assistant>("{}")

        assertFalse(assistant.acceptsAssistantCalls)
    }

    @Test
    fun `assistant call permission is serialized`() {
        val encoded = json.encodeToString(Assistant(acceptsAssistantCalls = true))
        val decoded = json.decodeFromString<Assistant>(encoded)

        assertTrue(decoded.acceptsAssistantCalls)
    }

    @Test
    fun `missing assistant call permission defaults to false after serialization round trip`() {
        val decoded = json.decodeFromString<Assistant>("{}")

        assertFalse(decoded.acceptsAssistantCalls)
    }

    @Test
    fun `delegate assistant option uses stable serial name`() {
        assertEquals(
            "delegate_assistant",
            LocalToolOption.DelegateAssistant.serializer().descriptor.serialName,
        )
    }
}
