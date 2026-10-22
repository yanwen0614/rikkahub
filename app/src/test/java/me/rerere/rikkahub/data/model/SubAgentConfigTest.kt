package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspaceId = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        systemPrompt = "You are a helpful assistant",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 4096,
        reasoningLevel = ReasoningLevel.HIGH,
        contextMessageLimit = 100,
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard),
        enabledSkills = setOf("skill-a", "skill-b"),
        mcpServers = setOf(mcpServer),
        enableWebSearch = true,
        workspaceId = workspaceId,
        enableMemory = true,
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
    )

    @Test
    fun `resolve inherits all fields from parent when config is empty`() {
        val config = SubAgentConfig(
            name = "empty-config",
            icon = "⚙️",
            description = "An empty config that inherits everything",
        )

        val snapshot = config.resolve(assistant, settings())

        assertEquals(config.id, snapshot.configId)
        assertEquals("empty-config", snapshot.name)
        assertEquals("⚙️", snapshot.icon)
        assertEquals("An empty config that inherits everything", snapshot.description)
        assertEquals(assistant.systemPrompt, snapshot.systemPrompt)
        assertEquals(assistant.chatModelId, snapshot.chatModelId)
        assertEquals(assistant.temperature, snapshot.temperature)
        assertEquals(assistant.topP, snapshot.topP)
        assertEquals(assistant.maxTokens, snapshot.maxTokens)
        assertEquals(assistant.reasoningLevel, snapshot.reasoningLevel)
        assertEquals(assistant.contextMessageLimit, snapshot.contextMessageLimit)
        assertEquals(assistant.localTools, snapshot.localTools)
        assertEquals(assistant.enabledSkills, snapshot.enabledSkills)
        assertEquals(assistant.mcpServers, snapshot.mcpServers)
        assertEquals(assistant.enableWebSearch, snapshot.enableWebSearch)
        assertEquals(assistant.workspaceId, snapshot.workspaceId)
        assertEquals(assistant.enableMemory, snapshot.enableMemory)
    }

    @Test
    fun `resolve uses config values over parent when set`() {
        val config = SubAgentConfig(
            name = "override-test",
            systemPrompt = "You are a specialized sub-agent",
            temperature = 0.1f,
            topP = 0.5f,
            maxTokens = 2048,
            reasoningLevel = ReasoningLevel.LOW,
            contextMessageLimit = 50,
            localTools = listOf(LocalToolOption.JavascriptEngine),
            enabledSkills = setOf("skill-c"),
            enableWebSearch = false,
            enableMemory = false,
        )

        val snapshot = config.resolve(assistant, settings())

        assertEquals("override-test", snapshot.name)
        assertEquals("You are a specialized sub-agent", snapshot.systemPrompt)
        assertEquals(0.1f, snapshot.temperature!!, 0.001f)
        assertEquals(0.5f, snapshot.topP!!, 0.001f)
        assertEquals(2048, snapshot.maxTokens)
        assertEquals(ReasoningLevel.LOW, snapshot.reasoningLevel)
        assertEquals(50, snapshot.contextMessageLimit)
        assertEquals(listOf(LocalToolOption.JavascriptEngine), snapshot.localTools)
        assertEquals(setOf("skill-c"), snapshot.enabledSkills)
        assertEquals(false, snapshot.enableWebSearch)
        assertEquals(false, snapshot.enableMemory)
    }

    @Test
    fun `resolve inherits system prompt when empty`() {
        val config = SubAgentConfig(
            name = "inherit-prompt",
            systemPrompt = "",
        )

        val snapshot = config.resolve(assistant, settings())

        assertEquals(assistant.systemPrompt, snapshot.systemPrompt)
    }

    @Test
    fun `resolve filters invalid model IDs`() {
        val nonexistentModelId = Uuid.random()
        val config = SubAgentConfig(
            name = "invalid-model",
            chatModelId = nonexistentModelId,
        )

        // Config's model ID doesn't exist, falls back to parent's modelA
        val snapshot = config.resolve(assistant, settings())

        assertEquals(modelA.id, snapshot.chatModelId)
    }

    @Test
    fun `resolve falls back to null when both config and parent model IDs are invalid`() {
        val nonexistentModelId = Uuid.random()
        val config = SubAgentConfig(
            name = "both-invalid",
            chatModelId = nonexistentModelId,
        )
        val parentWithInvalidModel = assistant.copy(chatModelId = null)

        val snapshot = config.resolve(parentWithInvalidModel, settings())

        assertNull(snapshot.chatModelId)
    }

    @Test
    fun `resolve filters invalid MCP server IDs`() {
        val nonexistentServerId = Uuid.random()
        val config = SubAgentConfig(
            name = "invalid-mcp",
            mcpServers = setOf(nonexistentServerId),
        )

        val snapshot = config.resolve(assistant, settings())

        assertTrue(snapshot.mcpServers.isEmpty())
    }

    @Test
    fun `resolve filters out only invalid MCP server IDs keeping valid ones`() {
        val validServer1 = mcpServer
        val validServer2 = Uuid.random()
        val invalidServer = Uuid.random()

        val settingsWithTwoServers = settings().copy(
            mcpServers = listOf(
                McpServerConfig.StreamableHTTPServer(id = validServer1),
                McpServerConfig.StreamableHTTPServer(id = validServer2),
            ),
        )

        val config = SubAgentConfig(
            name = "mixed-mcp",
            mcpServers = setOf(validServer1, validServer2, invalidServer),
        )

        val snapshot = config.resolve(assistant, settingsWithTwoServers)

        assertEquals(setOf(validServer1, validServer2), snapshot.mcpServers)
    }

    @Test
    fun `workspaceMode INHERIT uses parent workspaceId`() {
        val config = SubAgentConfig(
            name = "inherit-workspace",
            workspaceMode = SubAgentWorkspaceMode.INHERIT,
        )

        val snapshot = config.resolve(assistant, settings())

        assertEquals(workspaceId, snapshot.workspaceId)
    }

    @Test
    fun `workspaceMode NONE sets workspaceId to null`() {
        val config = SubAgentConfig(
            name = "no-workspace",
            workspaceMode = SubAgentWorkspaceMode.NONE,
        )

        val snapshot = config.resolve(assistant, settings())

        assertNull(snapshot.workspaceId)
    }

    @Test
    fun `workspaceMode SPECIFIC uses config workspaceId`() {
        val specificWorkspaceId = Uuid.random()
        val config = SubAgentConfig(
            name = "specific-workspace",
            workspaceMode = SubAgentWorkspaceMode.SPECIFIC,
            workspaceId = specificWorkspaceId,
        )

        val snapshot = config.resolve(assistant, settings())

        assertEquals(specificWorkspaceId, snapshot.workspaceId)
    }

    @Test
    fun `workspaceMode SPECIFIC with null workspaceId`() {
        val config = SubAgentConfig(
            name = "specific-null-workspace",
            workspaceMode = SubAgentWorkspaceMode.SPECIFIC,
            workspaceId = null,
        )

        val snapshot = config.resolve(assistant, settings())

        assertNull(snapshot.workspaceId)
    }

    @Test
    fun `resolve is serializable to and from JSON`() {
        val config = SubAgentConfig(
            name = "serialization-test",
            systemPrompt = "Custom prompt",
            temperature = 0.3f,
            reasoningLevel = ReasoningLevel.MAX,
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.JavascriptEngine),
            enabledSkills = setOf("skill-x"),
            mcpServers = setOf(mcpServer),
            enableWebSearch = false,
            workspaceMode = SubAgentWorkspaceMode.NONE,
            enableMemory = true,
        )

        val snapshot = config.resolve(assistant, settings())
        val json = me.rerere.rikkahub.utils.JsonInstant.encodeToString(snapshot)
        val decoded = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<SubAgentSnapshot>(json)

        assertEquals(snapshot, decoded)
    }
}
