package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import kotlin.uuid.Uuid

/**
 * 子代理的配置定义，嵌套在 [Assistant.subAgents] 里。
 *
 * 所有可继承字段（chatModelId / temperature / topP / maxTokens / reasoningLevel /
 * contextMessageLimit / localTools / enabledSkills / mcpServers / enableWebSearch /
 * enableMemory）均为 null 时表示从父 Assistant 继承。
 */
@Serializable
data class SubAgentConfig(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val icon: String = "🔬",
    val description: String = "",
    val systemPrompt: String = "",
    // Model & params (null = inherit from parent Assistant)
    val chatModelId: Uuid? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val contextMessageLimit: Int? = null,
    // Tool permissions (null = inherit)
    val localTools: List<LocalToolOption>? = null,
    val enabledSkills: Set<String>? = null,
    val mcpServers: Set<Uuid>? = null,
    val enableWebSearch: Boolean? = null,
    // Workspace
    val workspaceMode: SubAgentWorkspaceMode = SubAgentWorkspaceMode.INHERIT,
    val workspaceId: Uuid? = null,
    // Memory
    val enableMemory: Boolean? = null,
)

/**
 * 子代理的工作区模式。
 */
@Serializable
enum class SubAgentWorkspaceMode {
    /** 继承父助手的 workspaceId。 */
    INHERIT,

    /** 不启用工作区。 */
    NONE,

    /** 使用 [SubAgentConfig.workspaceId] 指定的工作区。 */
    SPECIFIC,
}

/**
 * [SubAgentConfig] 解析后的不可变快照，所有字段均为物化后的值。
 *
 * 创建子会话时由 [SubAgentConfig.resolve] 生成，之后不随配置变更而改变。
 */
@Serializable
data class SubAgentSnapshot(
    val configId: Uuid,
    val name: String,
    val icon: String,
    val description: String,
    val systemPrompt: String,
    val chatModelId: Uuid?,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val contextMessageLimit: Int,
    val localTools: List<LocalToolOption>,
    val enabledSkills: Set<String>,
    val mcpServers: Set<Uuid>,
    val enableWebSearch: Boolean,
    val workspaceId: Uuid?,
    val enableMemory: Boolean,
)

/**
 * 根据父 [Assistant] 和全局 [Settings] 解析 [SubAgentConfig] 为完整的 [SubAgentSnapshot]。
 *
 * 继承规则：
 * - 空字符串 systemPrompt 继承父助手的 systemPrompt。
 * - 所有 null 字段继承父助手的对应值。
 * - 模型 ID 先尝试 config 指定的，无效则回退到父助手的，再无效则为 null。
 * - MCP Server ID 只保留 settings.mcpServers 中存在的。
 * - workspaceId 受 [SubAgentWorkspaceMode] 控制。
 */
fun SubAgentConfig.resolve(
    parent: Assistant,
    settings: Settings,
): SubAgentSnapshot {
    fun modelId(candidate: Uuid?, fallback: Uuid?): Uuid? =
        candidate?.takeIf { settings.findModelById(it) != null }
            ?: fallback?.takeIf { settings.findModelById(it) != null }

    return SubAgentSnapshot(
        configId = id,
        name = name,
        icon = icon,
        description = description,
        systemPrompt = systemPrompt.ifBlank { parent.systemPrompt },
        chatModelId = modelId(chatModelId, parent.chatModelId),
        temperature = temperature ?: parent.temperature,
        topP = topP ?: parent.topP,
        maxTokens = maxTokens ?: parent.maxTokens,
        reasoningLevel = reasoningLevel ?: parent.reasoningLevel,
        contextMessageLimit = contextMessageLimit ?: parent.contextMessageLimit,
        localTools = localTools ?: parent.localTools,
        enabledSkills = enabledSkills ?: parent.enabledSkills,
        mcpServers = (mcpServers ?: parent.mcpServers)
            .filterTo(mutableSetOf()) { id -> settings.mcpServers.any { it.id == id } },
        enableWebSearch = enableWebSearch ?: parent.enableWebSearch,
        workspaceId = when (workspaceMode) {
            SubAgentWorkspaceMode.INHERIT -> parent.workspaceId
            SubAgentWorkspaceMode.NONE -> null
            SubAgentWorkspaceMode.SPECIFIC -> workspaceId
        },
        enableMemory = enableMemory ?: parent.enableMemory,
    )
}
