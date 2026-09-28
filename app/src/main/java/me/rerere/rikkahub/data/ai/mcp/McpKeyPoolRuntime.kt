package me.rerere.rikkahub.data.ai.mcp

import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.splitApiKeys
import kotlin.uuid.Uuid

// 请求头值中的 key 占位符，精确匹配、区分大小写
const val MCP_KEY_PLACEHOLDER = "\${key}"

// 单次调用最多尝试的不同 key 数量
const val MCP_KEY_MAX_ATTEMPTS = 3

/** 判断 HTTP 错误是否为 key 失败（鉴权/配额类），命中则换 key 重试。 */
fun isMcpKeyFailureCode(code: Int?): Boolean {
    return code == 401 || code == 402 || code == 403 || code == 429
}

/** 从异常链中提取 MCP SDK 的 HTTP 状态码。 */
fun extractMcpHttpCode(error: Throwable): Int? {
    var cur: Throwable? = error
    while (cur != null) {
        // StreamableHttpClientTransport 抛 StreamableHttpError(code, body)，code 为 public
        runCatching {
            val field = cur.javaClass.getDeclaredField("code")
            field.isAccessible = true
            val value = field.get(cur)
            when (value) {
                is Int -> return value
                is Number -> return value.toInt()
            }
        }
        cur = cur.cause
    }
    // SSE 回退到消息文本解析（形如 "Error POSTing to endpoint (HTTP 401 ...)"）
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    val sseCode = Regex("""HTTP\s+(\d{3})""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (sseCode != null) return sseCode
    // 兜底：消息中直接出现 401/403/429 等
    if (message.contains("401")) return 401
    if (message.contains("402")) return 402
    if (message.contains("403")) return 403
    if (message.contains("429")) return 429
    return null
}

/** 当前 key 是否为 key 失败。 */
fun isMcpKeyFailure(error: Throwable): Boolean {
    return isMcpKeyFailureCode(extractMcpHttpCode(error))
}

/** 刷新周期小时数转毫秒，最小 1 小时。 */
fun quotaRefreshMillis(hours: Int): Long {
    return (hours.coerceAtLeast(1)).toLong() * 60L * 60L * 1000L
}

/**
 * MCP key 池运行时状态（绝不写回 Settings）。
 * - 解析 key 列表（与 KeyRoulette 相同分隔规则）
 * - 同一请求内多个占位符共用同一个 key
 * - 每个请求（操作）都轮换选 key
 */
class McpKeyPoolRuntime(
    private val roulette: KeyRoulette,
) {
    /** 解析共享池引用，找不到或为空时返回 null（回退内联）。 */
    fun effectivePool(config: McpServerConfig, pools: List<McpKeyPool>): McpKeyPool? {
        val id = config.commonOptions.keyPoolId ?: return null
        return pools.find { it.id == id }
    }

    /** 有效 Key 字符串：共享池优先，否则内联 keys。 */
    fun effectiveKeys(config: McpServerConfig, pools: List<McpKeyPool>): String {
        return effectivePool(config, pools)?.keys ?: config.commonOptions.keys
    }

    /** 有效刷新周期小时数：共享池优先，否则内联。 */
    fun effectiveQuotaRefreshHours(config: McpServerConfig, pools: List<McpKeyPool>): Int {
        return effectivePool(config, pools)?.quotaRefreshHours ?: config.commonOptions.quotaRefreshHours
    }

    /** 有效桶 ID：共享池多 Server 共用一桶，否则按 Server 隔离。 */
    fun effectiveProviderId(config: McpServerConfig, pools: List<McpKeyPool>): String {
        val pool = effectivePool(config, pools)
        return if (pool != null) "mcp-pool-${pool.id}" else config.id.toString()
    }

    /** 该服务器是否启用 key 池。 */
    fun hasKeyPool(config: McpServerConfig, pools: List<McpKeyPool> = emptyList()): Boolean {
        return splitApiKeys(effectiveKeys(config, pools)).isNotEmpty()
    }

    /** 请求头中是否有 ${key} 占位符。 */
    fun hasPlaceholder(config: McpServerConfig): Boolean {
        return config.commonOptions.headers.any { it.second.contains(MCP_KEY_PLACEHOLDER) }
    }

    /** URL 中是否误用了占位符（不支持，仅用于 UI 校验提示）。 */
    fun urlHasPlaceholder(config: McpServerConfig): Boolean {
        return config.serverUrl.contains(MCP_KEY_PLACEHOLDER)
    }

    /** 为单次操作选择一个 key，无可用返回 null（全部冷却时直接失败）。 */
    fun selectKey(
        config: McpServerConfig,
        excluded: Set<String> = emptySet(),
        pools: List<McpKeyPool> = emptyList(),
    ): String? {
        val keys = effectiveKeys(config, pools)
        if (splitApiKeys(keys).isEmpty()) return null
        return roulette.nextExcluding(keys, effectiveProviderId(config, pools), excluded)
    }

    /** 用指定 key 解析请求头占位符，无 key 时返回原始值。 */
    fun resolveHeaders(
        config: McpServerConfig,
        key: String?,
    ): List<Pair<String, String>> {
        val base = config.resolvedHeadersForKeyPool()
        if (key == null) return base
        return base.map { (name, value) ->
            if (value.contains(MCP_KEY_PLACEHOLDER)) {
                name to value.replace(MCP_KEY_PLACEHOLDER, key)
            } else {
                name to value
            }
        }
    }

    fun reportFailure(config: McpServerConfig, key: String, pools: List<McpKeyPool> = emptyList()) {
        roulette.reportFailure(key, effectiveProviderId(config, pools), quotaRefreshMillis(effectiveQuotaRefreshHours(config, pools)))
    }

    fun reportSuccess(config: McpServerConfig, key: String, pools: List<McpKeyPool> = emptyList()) {
        roulette.reportSuccess(key, effectiveProviderId(config, pools))
    }

    fun snapshot(config: McpServerConfig, pools: List<McpKeyPool> = emptyList()) = roulette.snapshot(
        effectiveKeys(config, pools),
        effectiveProviderId(config, pools),
    )

    /** 手动重置：立即恢复该有效桶内所有待刷新 key（保留轮询进度）。 */
    fun resetCooldown(config: McpServerConfig, pools: List<McpKeyPool> = emptyList()) {
        roulette.resetCooldown(effectiveProviderId(config, pools))
    }

    /** 池页直查：不依赖某个 Server 的快照。 */
    fun snapshotForPool(pool: McpKeyPool) = roulette.snapshot(
        pool.keys,
        "mcp-pool-${pool.id}",
    )

    /** 池页直调：手动重置整个池。 */
    fun resetPool(pool: McpKeyPool) {
        roulette.resetCooldown("mcp-pool-${pool.id}")
    }

    fun providerId(serverId: Uuid): String = serverId.toString()
}

/**
 * 供运行时使用的 header 解析：keys 非空时视为手动鉴权，
 * 不注入 OAuth token（与 needsAuthorization 短路保持一致）。
 * 注意：返回的仍是原始值（含 ${key} 字面量），绝不把选中的 key 纳入连接参数。
 */
internal fun McpServerConfig.resolvedHeadersForKeyPool(pools: List<McpKeyPool> = emptyList()): List<Pair<String, String>> {
    // 空名称请求头直接过滤（与 resolvedHeaders 的上游修复保持一致，避免 OkHttp "name is empty"）
    val base = commonOptions.headers.filter { it.first.isNotBlank() }
    // key 池模式下视为手动鉴权，不注入 OAuth token
    // 共享池引用时同样短路（即使 pools 未传入也按手动鉴权处理，避免误注 token）
    if (commonOptions.keyPoolId != null) return base
    if (splitApiKeys(McpKeyPoolRuntime(KeyRoulette.default()).effectiveKeys(this, pools)).isNotEmpty()) return base
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}
