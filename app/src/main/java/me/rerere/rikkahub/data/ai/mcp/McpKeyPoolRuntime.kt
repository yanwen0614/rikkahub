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

/** 冷却小时数转毫秒，最小 1 小时。 */
fun mcpCooldownMillis(hours: Int): Long {
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
    /** 该服务器是否启用 key 池。 */
    fun hasKeyPool(config: McpServerConfig): Boolean {
        return splitApiKeys(config.commonOptions.keys).isNotEmpty()
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
    fun selectKey(config: McpServerConfig, excluded: Set<String> = emptySet()): String? {
        val keys = config.commonOptions.keys
        if (splitApiKeys(keys).isEmpty()) return null
        return roulette.nextExcluding(keys, config.id.toString(), excluded)
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

    fun reportFailure(config: McpServerConfig, key: String) {
        roulette.reportFailure(key, config.id.toString(), mcpCooldownMillis(config.commonOptions.keyCooldownHours))
    }

    fun reportSuccess(config: McpServerConfig, key: String) {
        roulette.reportSuccess(key, config.id.toString())
    }

    fun snapshot(config: McpServerConfig) = roulette.snapshot(
        config.commonOptions.keys,
        config.id.toString(),
    )

    fun providerId(serverId: Uuid): String = serverId.toString()
}

/**
 * 供运行时使用的 header 解析：keys 非空时视为手动鉴权，
 * 不注入 OAuth token（与 needsAuthorization 短路保持一致）。
 * 注意：返回的仍是原始值（含 ${key} 字面量），绝不把选中的 key 纳入连接参数。
 */
internal fun McpServerConfig.resolvedHeadersForKeyPool(): List<Pair<String, String>> {
    val base = commonOptions.headers
    // key 池模式下视为手动鉴权，不注入 OAuth token
    if (splitApiKeys(commonOptions.keys).isNotEmpty()) return base
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}
