package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentSettingsOverlayTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val modelC = Model(modelId = "model-c", displayName = "Model C")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        name = "Parent Assistant",
        systemPrompt = "Parent system prompt",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 4096,
        reasoningLevel = ReasoningLevel.HIGH,
        contextMessageLimit = 100,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill-a"),
        enableMemory = true,
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private val subAgentSnapshot = SubAgentSnapshot(
        configId = Uuid.random(),
        name = "Sub Agent",
        icon = "🔬",
        description = "A sub-agent",
        systemPrompt = "Sub-agent system prompt",
        chatModelId = modelB.id,
        temperature = 0.1f,
        topP = 0.5f,
        maxTokens = 2048,
        reasoningLevel = ReasoningLevel.LOW,
        contextMessageLimit = 50,
        localTools = listOf(LocalToolOption.JavascriptEngine),
        enabledSkills = setOf("skill-b"),
        mcpServers = emptySet(),
        enableWebSearch = false,
        workspaceId = null,
        enableMemory = false,
    )

    private val subAgentSnapshotNoModel = SubAgentSnapshot(
        configId = Uuid.random(),
        name = "Sub Agent No Model",
        icon = "🔬",
        description = "Sub-agent without model",
        systemPrompt = "No model sub-agent",
        chatModelId = null,
        temperature = null,
        topP = null,
        maxTokens = 1024,
        reasoningLevel = ReasoningLevel.AUTO,
        contextMessageLimit = 30,
        localTools = emptyList(),
        enabledSkills = emptySet(),
        mcpServers = emptySet(),
        enableWebSearch = false,
        workspaceId = null,
        enableMemory = false,
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelC.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB, modelC))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(
        assistant: Assistant = this.assistant,
        snapshot: SubAgentSnapshot? = null,
    ) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
        subAgentSnapshot = snapshot,
    )

    @Test
    fun `getAssistantOf with subAgentSnapshot returns sub-agent values`() {
        val conv = conversation(snapshot = subAgentSnapshot)
        val resolved = settings().getAssistantOf(conv)

        assertEquals("Sub Agent", resolved.name)
        assertEquals(Avatar.Emoji("🔬"), resolved.avatar)
        assertEquals(true, resolved.useAssistantAvatar)
        assertEquals("Sub-agent system prompt", resolved.systemPrompt)
        assertEquals(modelB.id, resolved.chatModelId)
        assertEquals(0.1f, resolved.temperature!!, 0.001f)
        assertEquals(0.5f, resolved.topP!!, 0.001f)
        assertEquals(2048, resolved.maxTokens)
        assertEquals(ReasoningLevel.LOW, resolved.reasoningLevel)
        assertEquals(50, resolved.contextMessageLimit)
        assertEquals(listOf(LocalToolOption.JavascriptEngine), resolved.localTools)
        assertEquals(setOf("skill-b"), resolved.enabledSkills)
        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertEquals(false, resolved.enableWebSearch)
        assertNull(resolved.workspaceId)
        assertEquals(false, resolved.enableMemory)
    }

    @Test
    fun `getAssistantOf without subAgentSnapshot behaves normally`() {
        val conv = conversation(snapshot = null)
        val resolved = settings().getAssistantOf(conv)

        assertEquals("Parent Assistant", resolved.name)
        assertEquals(modelA.id, resolved.chatModelId)
        assertEquals("Parent system prompt", resolved.systemPrompt)
        assertEquals(setOf(mcpServer), resolved.mcpServers)
        assertEquals(setOf(injection), resolved.modeInjectionIds)
        assertEquals(setOf(lorebook), resolved.lorebookIds)
    }

    @Test
    fun `getChatModelOf with sub-agent model`() {
        val conv = conversation(snapshot = subAgentSnapshot)
        val model = settings().getChatModelOf(conv)

        assertEquals(modelB.id, model?.id)
        assertEquals("model-b", model?.modelId)
        assertFalse(BuiltInTools.Search in model!!.tools)
    }

    @Test
    fun `getChatModelOf fallback when sub-agent model deleted`() {
        // 只有 modelA 和 modelC 可用，modelB 已被删除
        val settingsWithoutModelB = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelC))),
        )
        val conv = conversation(snapshot = subAgentSnapshot)
        val model = settingsWithoutModelB.getChatModelOf(conv)

        // 子代理的 modelB 不存在，fallback 到父助手的 modelA
        assertEquals(modelA.id, model?.id)
    }

    @Test
    fun `getChatModelOf fallback to global default when both sub-agent and parent models are deleted`() {
        val settingsWithoutModelsAB = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelC))),
        )
        val conv = conversation(snapshot = subAgentSnapshotNoModel)
        val model = settingsWithoutModelsAB.getChatModelOf(conv)

        // 子代理 chatModelId=null，父助手不存在 → fallback 到全局默认 modelC
        assertEquals(modelC.id, model?.id)
    }

    @Test
    fun `withSubAgent clears subAgents list`() {
        val withConfigs = assistant.copy(subAgents = listOf(SubAgentConfig(name = "nested")))
        val result = withConfigs.withSubAgent(subAgentSnapshot)

        assertEquals(emptyList<SubAgentConfig>(), result.subAgents)
    }

    @Test
    fun `withSubAgent clears presetMessages and quickMessageIds`() {
        val withMessages = assistant.copy(
            presetMessages = listOf(me.rerere.ai.ui.UIMessage.system("preset")),
            quickMessageIds = setOf(Uuid.random()),
        )
        val result = withMessages.withSubAgent(subAgentSnapshot)

        assertEquals(emptyList<me.rerere.ai.ui.UIMessage>(), result.presetMessages)
        assertEquals(emptySet<Uuid>(), result.quickMessageIds)
        assertFalse(result.enableRecentChatsReference)
    }

    @Test
    fun `toConversationConfig with builtInSearch=true`() {
        val config = assistant.toConversationConfig(builtInSearch = true)

        assertEquals(modelA.id, config.chatModelId)
        assertEquals(ReasoningLevel.HIGH, config.reasoningLevel)
        assertEquals(true, config.enableWebSearch)
        assertEquals(true, config.builtInSearch)
        assertEquals(setOf(mcpServer), config.mcpServers)
        assertEquals(workspace, config.workspaceId)
        assertEquals(setOf("skill-a"), config.enabledSkills)
    }

    @Test
    fun `toConversationConfig with builtInSearch=false`() {
        val config = assistant.toConversationConfig(builtInSearch = false)

        assertEquals(false, config.builtInSearch)
    }
}
