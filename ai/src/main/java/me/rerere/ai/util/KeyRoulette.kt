package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// key 分隔规则：空格/换行/逗号
private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

/** 切分 key 池字符串，trim、去空、去重，供各模块复用，保持一致行为。 */
fun splitApiKeys(keys: String): List<String> {
    return keys
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/** key 池快照，只读展示用。 */
@Serializable
data class KeyPoolSnapshot(
    // key 总数
    val total: Int = 0,
    // 冷却中的 key 数量
    val cooling: Int = 0,
    // 最近一个冷却 key 的恢复时间（epoch millis），无冷却时为 null
    val nextRecoveryAtMillis: Long? = null,
)

interface KeyRoulette {
    fun next(keys: String, providerId: String = ""): String

    /** 跳过冷却中与 excluded 的 key 选择下一个，全部不可用返回 null。默认实现无持久化。 */
    fun nextExcluding(keys: String, providerId: String, excluded: Set<String>): String? {
        val list = splitApiKeys(keys).filter { it !in excluded }
        if (list.isEmpty()) return null
        return list.random()
    }

    /** 上报 key 失败，进入固定冷却。默认空实现。 */
    fun reportFailure(key: String, providerId: String, cooldownMillis: Long) {}

    /** 上报 key 成功，清除冷却。默认空实现。 */
    fun reportSuccess(key: String, providerId: String) {}

    /** 只读快照。默认返回空快照。 */
    fun snapshot(keys: String, providerId: String): KeyPoolSnapshot {
        return KeyPoolSnapshot(total = splitApiKeys(keys).size)
    }

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 filesDir/key_pool_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(
            storage = FileKeyPoolStorage(File(context.filesDir, KEY_POOL_FILE)),
        )

        /** 单测/无 Context 环境用：基于外部存储构造。 */
        fun lru(storage: KeyPoolStorage, clock: () -> Long = System::currentTimeMillis): KeyRoulette =
            LruKeyRoulette(storage = storage, clock = clock)
    }
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String): String {
        val keyList = splitApiKeys(keys)
        return if (keyList.isNotEmpty()) {
            keyList.random()
        } else {
            keys
        }
    }
}

internal const val KEY_POOL_FILE = "key_pool_roulette.json"
internal const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

/** 单个 key 的运行时状态。 */
@Serializable
data class KeyState(
    // 上次使用时间
    val lastUsedAt: Long = 0L,
    // 冷却截止时间（epoch millis），0 表示未冷却
    val cooldownUntil: Long = 0L,
)

// 文件结构: Map<providerId, Map<apiKey, KeyState>>
typealias KeyPoolCache = Map<String, Map<String, KeyState>>

/** key 池持久化抽象，默认基于文件，方便 JVM 单测注入内存实现。 */
interface KeyPoolStorage {
    fun load(): KeyPoolCache
    fun save(cache: KeyPoolCache)
}

/** 基于文件的默认实现。 */
class FileKeyPoolStorage(
    private val file: File,
) : KeyPoolStorage {
    override fun load(): KeyPoolCache {
        return try {
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun save(cache: KeyPoolCache) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}

/** 内存实现，供单测使用。 */
class InMemoryKeyPoolStorage(
    initial: KeyPoolCache = emptyMap(),
) : KeyPoolStorage {
    private var cache: KeyPoolCache = initial
    override fun load(): KeyPoolCache = cache
    override fun save(cache: KeyPoolCache) {
        this.cache = cache
    }
}

internal class LruKeyRoulette(
    private val storage: KeyPoolStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitApiKeys(keys)
        if (keyList.isEmpty()) return keys
        // 兼容旧语义：优先跳过冷却 key；全部冷却时回退到 LRU，绝不返回空
        return nextExcluding(keys, providerId, emptySet())
            ?: lruPick(keyList, emptyMap(), clock())
    }

    override fun nextExcluding(keys: String, providerId: String, excluded: Set<String>): String? {
        val keyList = splitApiKeys(keys)
        if (keyList.isEmpty()) return null
        synchronized(LruFileLock) {
            val now = clock()
            val allCache = storage.load().toMutableMap()
            val providerCache = filterProviderCache(allCache[providerId], keyList, now).toMutableMap()

            val candidates = keyList.filter { it !in excluded && !isCooling(providerCache[it], now) }
            if (candidates.isEmpty()) return null

            // 优先从未使用，否则最久未使用
            val selected = candidates.firstOrNull { it !in providerCache }
                ?: candidates.minByOrNull { providerCache[it]?.lastUsedAt ?: 0L }!!

            providerCache[selected] = (providerCache[selected] ?: KeyState()).copy(lastUsedAt = now)
            allCache[providerId] = providerCache
            cleanupExpiredProviders(allCache, providerId, now)
            storage.save(allCache)
            return selected
        }
    }

    override fun reportFailure(key: String, providerId: String, cooldownMillis: Long) {
        if (key.isBlank() || cooldownMillis <= 0) return
        synchronized(LruFileLock) {
            val now = clock()
            val allCache = storage.load().toMutableMap()
            val providerCache = allCache[providerId]?.toMutableMap() ?: mutableMapOf()
            val state = providerCache[key] ?: KeyState()
            providerCache[key] = state.copy(cooldownUntil = now + cooldownMillis)
            allCache[providerId] = providerCache
            storage.save(allCache)
        }
    }

    override fun reportSuccess(key: String, providerId: String) {
        if (key.isBlank()) return
        synchronized(LruFileLock) {
            val allCache = storage.load().toMutableMap()
            val providerCache = allCache[providerId]?.toMutableMap() ?: return
            val state = providerCache[key] ?: return
            if (state.cooldownUntil == 0L) return
            // 清除冷却，保留 lastUsedAt 以继续轮询
            providerCache[key] = state.copy(cooldownUntil = 0L)
            allCache[providerId] = providerCache
            storage.save(allCache)
        }
    }

    override fun snapshot(keys: String, providerId: String): KeyPoolSnapshot {
        val keyList = splitApiKeys(keys)
        if (keyList.isEmpty()) return KeyPoolSnapshot()
        synchronized(LruFileLock) {
            val now = clock()
            val providerCache = filterProviderCache(storage.load()[providerId], keyList, now)
            val coolingUntil = keyList.mapNotNull { k ->
                providerCache[k]?.cooldownUntil?.takeIf { it > now }
            }
            return KeyPoolSnapshot(
                total = keyList.size,
                cooling = coolingUntil.size,
                nextRecoveryAtMillis = coolingUntil.minOrNull(),
            )
        }
    }

    private fun filterProviderCache(
        raw: Map<String, KeyState>?,
        keyList: List<String>,
        now: Long,
    ): Map<String, KeyState> {
        if (raw == null) return emptyMap()
        // 过滤不在当前 key 列表中的条目；过期 lastUsed 不删除，仅影响排序（从未使用优先）
        return raw.filter { (k, _) -> k in keyList }
    }

    private fun cleanupExpiredProviders(
        allCache: MutableMap<String, Map<String, KeyState>>,
        currentProviderId: String,
        now: Long,
    ) {
        allCache.entries.removeIf { (id, cache) ->
            id != currentProviderId && cache.values.all { now - it.lastUsedAt >= EXPIRE_DURATION_MS }
        }
    }

    private fun isCooling(state: KeyState?, now: Long): Boolean {
        return state != null && state.cooldownUntil > now
    }

    private fun lruPick(keyList: List<String>, providerCache: Map<String, KeyState>, now: Long): String {
        return keyList.firstOrNull { it !in providerCache }
            ?: providerCache.minByOrNull { it.value.lastUsedAt }?.key
            ?: keyList.first()
    }
}
