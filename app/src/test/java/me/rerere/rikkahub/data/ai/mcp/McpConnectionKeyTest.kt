package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class McpConnectionKeyTest {
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo"),
        url = "https://example.com/mcp",
    )

    @Test
    fun `tool metadata does not affect connection key`() {
        val withTools = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", enable = false))
            )
        )

        assertEquals(base.connectionKey(), withTools.connectionKey())
    }

    @Test
    fun `url transport and headers affect connection key`() {
        assertNotEquals(base.connectionKey(), base.copy(url = "https://example.com/other").connectionKey())
        assertNotEquals(
            base.connectionKey(),
            McpServerConfig.SseTransportServer(
                id = base.id,
                commonOptions = base.commonOptions,
                url = base.url,
            ).connectionKey()
        )
        assertNotEquals(
            base.connectionKey(),
            base.copy(
                commonOptions = base.commonOptions.copy(headers = listOf("X-API-Key" to "secret"))
            ).connectionKey()
        )
    }

    @Test
    fun `keys and placeholder do not affect connection key`() {
        // keys 列表变化不触发重连：选中 key 绝不纳入连接参数
        val withKeys = base.copy(
            commonOptions = base.commonOptions.copy(keys = "k1\nk2")
        )
        assertEquals(base.connectionKey(), withKeys.connectionKey())

        val withCooldown = withKeys.copy(
            commonOptions = withKeys.commonOptions.copy(quotaRefreshHours = 1)
        )
        assertEquals(base.connectionKey(), withCooldown.connectionKey())

        // 占位符字面量属于 header 值的一部分，header 变化仍触发重连
        val withPlaceholder = base.copy(
            commonOptions = base.commonOptions.copy(headers = listOf("Authorization" to "Bearer \${key}"))
        )
        assertNotEquals(base.connectionKey(), withPlaceholder.connectionKey())
        // 但 keys 变化不影响已含占位符的 connectionKey
        val withPlaceholderAndKeys = withPlaceholder.copy(
            commonOptions = withPlaceholder.commonOptions.copy(keys = "k1 k2")
        )
        assertEquals(withPlaceholder.connectionKey(), withPlaceholderAndKeys.connectionKey())
    }

    @Test
    fun `key pool mode skips oauth token in connection key`() {
        val oauth = McpOAuthState(enabled = true, accessToken = "oauth-token")
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        // keys 非空视为手动鉴权，不注入 OAuth token
        val withKeysAndOAuth = base.copy(
            commonOptions = base.commonOptions.copy(keys = "k1", oauth = oauth)
        )
        assertEquals(base.connectionKey(), withKeysAndOAuth.connectionKey())
    }

    @Test
    fun `oauth token affects connection key unless manual authorization header wins`() {
        val oauth = McpOAuthState(enabled = true, accessToken = "oauth-token")
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        assertNotEquals(base.connectionKey(), withOAuth.connectionKey())

        val manualAuth = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("Authorization" to "Bearer manual"),
                oauth = oauth,
            )
        )
        val manualAuthWithoutOAuth = manualAuth.copy(
            commonOptions = manualAuth.commonOptions.copy(oauth = null)
        )
        assertEquals(manualAuthWithoutOAuth.connectionKey(), manualAuth.connectionKey())
    }
}
