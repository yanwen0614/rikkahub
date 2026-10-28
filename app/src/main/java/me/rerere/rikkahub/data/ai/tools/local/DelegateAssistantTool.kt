package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class DelegateAssistantException(val code: String) : IllegalArgumentException(code)

internal fun delegationFailure(error: Throwable): DelegateAssistantException {
    if (error is DelegateAssistantException) return error
    val code = error.message?.substringBefore(':')?.takeIf {
        it in setOf("target_approval_required", "target_conversation_deleted", "target_generation_failed")
    } ?: "target_generation_failed"
    return DelegateAssistantException(code)
}

internal fun shouldCreateDelegateAssistantTool(enabled: Boolean, delegationAllowed: Boolean): Boolean =
    enabled && delegationAllowed

data class DelegateAssistantRequest(
    val sourceAssistantId: Uuid,
    val sourceConversationId: Uuid,
    val assistant: Assistant,
    val message: String,
    val conversationId: Uuid?,
    val createNew: Boolean,
)

/** Execution is deliberately injected by the owner of cross-session lifecycle and session safety. */
fun interface DelegateAssistantExecutor {
    suspend fun execute(request: DelegateAssistantRequest): List<UIMessagePart>
}

data class DelegateAssistantParams(
    val assistantName: String,
    val message: String,
    val conversationId: Uuid?,
    val createNew: Boolean,
)

internal fun parseDelegationParams(input: String): DelegateAssistantParams {
    val obj = runCatching { Json.parseToJsonElement(input).jsonObject }
        .getOrElse { throw DelegateAssistantException("invalid_arguments") }
    val assistantName = obj.string("assistant_name") ?: throw DelegateAssistantException("invalid_assistant_name")
    val message = obj.string("message") ?: throw DelegateAssistantException("invalid_message")
    val createNew = when (val value = obj["create_new"]) {
        null -> true
        is JsonPrimitive -> value.booleanOrNull ?: throw DelegateAssistantException("invalid_create_new")
        else -> throw DelegateAssistantException("invalid_create_new")
    }
    val conversationId = when (val value = obj["conversation_id"]) {
        null -> null
        is JsonPrimitive -> value.contentOrNull
            ?.let { runCatching { Uuid.parse(it) }.getOrElse { throw DelegateAssistantException("invalid_conversation_id") } }
            ?: throw DelegateAssistantException("invalid_conversation_id")
        else -> throw DelegateAssistantException("invalid_conversation_id")
    }
    if (createNew && conversationId != null) throw DelegateAssistantException("conversation_id_not_allowed")
    if (!createNew && conversationId == null) throw DelegateAssistantException("conversation_id_required")
    return DelegateAssistantParams(assistantName, message, conversationId, createNew)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun resolveDelegateAssistant(
    assistants: List<Assistant>,
    sourceAssistantId: Uuid,
    name: String,
): Assistant {
    val matches = assistants.filter { it.name == name }
    if (matches.isEmpty()) throw DelegateAssistantException("unknown_assistant")
    if (matches.size > 1) throw DelegateAssistantException("ambiguous_assistant_name")
    return matches.single().also {
        if (it.id == sourceAssistantId || !it.acceptsAssistantCalls) {
            throw DelegateAssistantException("assistant_not_allowed")
        }
    }
}

internal fun buildDelegateAssistantTool(
    settingsStore: SettingsStore,
    availableAssistantNames: List<String>,
    sourceAssistantId: Uuid,
    sourceConversationId: Uuid,
    executor: DelegateAssistantExecutor?,
): Tool = Tool(
    name = delegateAssistantToolName(),
    description = "Delegate a message to an assistant that accepts assistant calls. Available assistants: " +
        availableAssistantNames.joinToString(", "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("assistant_name") { put("type", "string") }
                putJsonObject("message") { put("type", "string") }
                putJsonObject("conversation_id") { put("type", "string") }
                putJsonObject("create_new") {
                    put("type", "boolean")
                    put("default", true)
                }
            },
            required = listOf("assistant_name", "message"),
        )
    },
    execute = { input ->
        try {
            val params = parseDelegationParams(input.toString())
            val settings = settingsStore.awaitLoaded()
            val assistant = resolveDelegateAssistant(settings.assistants, sourceAssistantId, params.assistantName)
            val enabledNames = settings.assistants.filter {
                it.id != sourceAssistantId && it.acceptsAssistantCalls
            }.map { it.name }
            if (assistant.name !in enabledNames) throw DelegateAssistantException("assistant_not_allowed")
            val boundExecutor = executor ?: throw DelegateAssistantException("executor_not_bound")
            val output = boundExecutor.execute(
                DelegateAssistantRequest(
                    sourceAssistantId = sourceAssistantId,
                    sourceConversationId = sourceConversationId,
                    assistant = assistant,
                    message = params.message,
                    conversationId = params.conversationId,
                    createNew = params.createNew,
                )
            )
            output
        } catch (e: DelegateAssistantException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw delegationFailure(e)
        }
    },
)

private fun delegateAssistantToolName(): String = "delegate_to_assistant"
