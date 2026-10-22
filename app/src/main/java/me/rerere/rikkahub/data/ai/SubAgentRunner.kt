package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

fun interface SubAgentRunner {
    suspend fun run(request: SubAgentRequest): SubAgentResult
}

data class SubAgentRequest(
    val parentConversationId: Uuid,
    val subAgentName: String,
    val task: String,
    val subSessionId: Uuid? = null,
)

data class SubAgentResult(
    val status: SubAgentStatus,
    val subSessionId: Uuid? = null,
    val name: String = "",
    val icon: String = "",
    val summary: String = "",
    val durationMs: Long? = null,
)

enum class SubAgentStatus { COMPLETED, CANCELLED, FAILED, AWAITING_APPROVAL }

class SubAgentRunnerHolder {
    @Volatile
    var runner: SubAgentRunner? = null
}

@Serializable
data class SubAgentToolPayload(
    val status: String,
    val sub_session_id: String = "",
    val summary: String,
)

@Serializable
data class SubAgentToolMetadata(
    val subSessionId: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val durationMs: Long? = null,
)

fun SubAgentToolMetadata.toJsonObject(): JsonObject =
    JsonInstant.encodeToJsonElement(this).jsonObject

fun UIMessagePart.subAgentMetadata(): SubAgentToolMetadata? =
    metadata?.let { runCatching { JsonInstant.decodeFromJsonElement<SubAgentToolMetadata>(it) }.getOrNull() }
