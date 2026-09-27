package me.rerere.search

import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.splitApiKeys
import me.rerere.search.SearchService.Companion.keyRoulette

// 单次请求最多尝试的不同 key 数量
const val KEY_POOL_MAX_ATTEMPTS = 3

/** 携带 HTTP 状态码的搜索请求异常，用于 key 失败分类。 */
class SearchHttpException(
    val code: Int,
    val body: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 判断 HTTP 状态码是否为 key 失败（鉴权/配额类），命中则换 key 重试。
 * 401/402/403/429 通用，432 为 Tavily 配额错误，一并视为 key 失败。
 * 其余状态码与网络错误不换 key。
 */
fun isKeyFailureHttpCode(code: Int): Boolean {
    return code == 401 || code == 402 || code == 403 || code == 429 || code == 432
}

/** 冷却小时数转毫秒，最小 1 小时。 */
fun keyCooldownHoursToMillis(hours: Int): Long {
    return (hours.coerceAtLeast(1)).toLong() * 60L * 60L * 1000L
}

private fun findSearchHttpException(e: Throwable): SearchHttpException? {
    var cur: Throwable? = e
    while (cur != null) {
        if (cur is SearchHttpException) return cur
        cur = cur.cause
    }
    return null
}

/**
 * key 池执行器：请求前 LRU 轮询选 key，key 触发限额时自动换 key 重试。
 * - keys 为空时直接用原字符串执行一次（兼容旧行为）
 * - 最多尝试 [maxAttempts] 个不同 key，单 key 不重复尝试
 * - 全部 key 冷却时直接返回失败，不降级
 */
suspend fun <T> withKeyRetry(
    keys: String,
    providerId: String,
    cooldownMillis: Long,
    maxAttempts: Int = KEY_POOL_MAX_ATTEMPTS,
    roulette: KeyRoulette = keyRoulette,
    block: suspend (apiKey: String) -> T,
): Result<T> {
    val keyList = splitApiKeys(keys)
    if (keyList.isEmpty()) {
        // 无 key 配置：执行一次，让业务层报参数错误
        return runCatching { block(keys) }
    }
    if (keyList.size == 1) {
        // 单 key：执行一次，失败也冷却该 key 但不重复尝试
        val single = keyList.first()
        // 单 key 且在冷却中：直接失败，不降级使用
        if (roulette.nextExcluding(keys, providerId, emptySet()) == null) {
            return Result.failure(
                SearchHttpException(-1, null, "All API keys are cooling down, please try later")
            )
        }
        return runCatching { block(single) }.onSuccess {
            roulette.reportSuccess(single, providerId)
        }.onFailure { e ->
            val http = findSearchHttpException(e)
            if (http != null && http.code != -1 && isKeyFailureHttpCode(http.code)) {
                roulette.reportFailure(single, providerId, cooldownMillis)
            }
        }
    }
    val attempted = mutableSetOf<String>()
    var lastError: Throwable? = null
    val attempts = maxAttempts.coerceAtLeast(1).coerceAtMost(keyList.size)
    repeat(attempts) {
        val key = roulette.nextExcluding(keys, providerId, attempted) ?: run {
            // 无可用 key（全部冷却）：直接失败
            lastError?.let { return Result.failure(it) }
            return Result.failure(
                SearchHttpException(-1, null, "All API keys are cooling down, please try later")
            )
        }
        attempted.add(key)
        try {
            val result = block(key)
            roulette.reportSuccess(key, providerId)
            return Result.success(result)
        } catch (e: Throwable) {
            val http = findSearchHttpException(e)
            if (http != null && http.code != -1 && isKeyFailureHttpCode(http.code)) {
                // key 失败：冷却后换 key 重试
                roulette.reportFailure(key, providerId, cooldownMillis)
                lastError = e
            } else {
                // 非 key 失败（网络错误/参数错误等）：立即返回
                return Result.failure(e)
            }
        }
    }
    return Result.failure(lastError ?: SearchHttpException(-1, null, "All API keys are cooling down, please try later"))
}
