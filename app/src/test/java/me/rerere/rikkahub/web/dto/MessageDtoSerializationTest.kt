package me.rerere.rikkahub.web.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageDtoSerializationTest {
    @Test
    fun `message dto wire allowlist does not expose runtime delegation correlation`() {
        val dto = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
        ).toDto()

        val json = Json.parseToJsonElement(Json.encodeToString(dto)).jsonObject
        assertEquals(
            setOf(
                "id", "role", "parts", "annotations", "createdAt", "finishedAt", "modelId",
                "modelSnapshot", "usage", "generationTimeMs", "translation", "isContextCheckpoint",
            ),
            json.keys,
        )
    }
}
