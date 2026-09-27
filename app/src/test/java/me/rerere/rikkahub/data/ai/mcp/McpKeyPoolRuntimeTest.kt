package me.rerere.rikkahub.data.ai.mcp

import me.rerere.ai.util.InMemoryKeyPoolStorage
import me.rerere.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpKeyPoolRuntimeTest {
    private fun runtime(): McpKeyPoolRuntime {
        return McpKeyPoolRuntime(KeyRoulette.lru(InMemoryKeyPoolStorage()))
    }

    private fun config(
        keys: String = "",
        headers: List<Pair<String, String>> = emptyList(),
        url: String = "https://example.com/mcp",
    ) = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo", headers = headers, keys = keys),
        url = url,
    )

    @Test
    fun `key 列表按相同分隔规则解析`() {
        val r = runtime()
        assertFalse(r.hasKeyPool(config()))
        assertTrue(r.hasKeyPool(config(keys = "k1 k2,k3\nk1")))
        assertTrue(r.snapshot(config(keys = "k1 k2 k3")).total == 3)
    }

    @Test
    fun `占位符精确匹配区分大小写`() {
        val r = runtime()
        val c = config(
            keys = "secret",
            headers = listOf("Authorization" to "Bearer \${key}"),
        )
        assertTrue(r.hasPlaceholder(c))
        assertFalse(r.hasPlaceholder(config(keys = "secret", headers = listOf("Authorization" to "Bearer \${KEY}"))))
        assertFalse(r.hasPlaceholder(config(keys = "secret")))

        // 同一请求内多个占位符共用同一个 key
        val multi = config(
            keys = "secret",
            headers = listOf(
                "Authorization" to "Bearer \${key}",
                "X-Key" to "prefix-\${key}-suffix",
                "Other" to "no-placeholder",
            ),
        )
        val resolved = r.resolveHeaders(multi, "secret")
        assertEquals("Bearer secret", resolved[0].second)
        assertEquals("prefix-secret-suffix", resolved[1].second)
        assertEquals("no-placeholder", resolved[2].second)
    }

    @Test
    fun `无 key 时返回原始 header`() {
        val r = runtime()
        val c = config(headers = listOf("Authorization" to "Bearer \${key}"))
        val resolved = r.resolveHeaders(c, null)
        assertEquals("Bearer \${key}", resolved[0].second)
    }

    @Test
    fun `url 占位符检测`() {
        val r = runtime()
        assertTrue(r.urlHasPlaceholder(config(url = "https://example.com/\${key}/mcp")))
        assertFalse(r.urlHasPlaceholder(config()))
    }

    @Test
    fun `每次操作轮换选 key`() {
        val r = runtime()
        val c = config(keys = "k1 k2", headers = listOf("Authorization" to "Bearer \${key}"))
        val first = r.selectKey(c)
        assertTrue(first == "k1" || first == "k2")
        // 排除已尝试后选到另一个
        val second = r.selectKey(c, setOf(first!!))
        assertTrue(second != null && second != first)
        // 全部排除后返回 null（调用方直接失败，不降级）
        assertNull(r.selectKey(c, setOf("k1", "k2")))
    }

    @Test
    fun `失败冷却成功清除`() {
        val r = runtime()
        val c = config(keys = "k1 k2")
        r.reportFailure(c, "k1")
        assertEquals(1, r.snapshot(c).cooling)
        assertEquals("k2", r.selectKey(c))
        r.reportSuccess(c, "k1")
        assertEquals(0, r.snapshot(c).cooling)
    }

    @Test
    fun `http code 提取与分类`() {
        assertTrue(isMcpKeyFailureCode(401))
        assertTrue(isMcpKeyFailureCode(402))
        assertTrue(isMcpKeyFailureCode(403))
        assertTrue(isMcpKeyFailureCode(429))
        assertFalse(isMcpKeyFailureCode(500))
        assertFalse(isMcpKeyFailureCode(null))

        // SSE 文本回退
        assertTrue(isMcpKeyFailure(Exception("Error POSTing to endpoint (HTTP 401 Unauthorized)")))
        assertTrue(isMcpKeyFailure(Exception("HTTP 429 too many requests")))
        assertFalse(isMcpKeyFailure(Exception("HTTP 500 boom")))
    }

    @Test
    fun `mcp 冷却时长最小 1 小时`() {
        assertEquals(24L * 3600_000L, mcpCooldownMillis(24))
        assertEquals(1L * 3600_000L, mcpCooldownMillis(0))
    }
}
