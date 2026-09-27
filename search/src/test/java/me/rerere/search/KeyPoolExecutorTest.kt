package me.rerere.search

import kotlinx.coroutines.runBlocking
import me.rerere.ai.util.InMemoryKeyPoolStorage
import me.rerere.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolExecutorTest {
    @Test
    fun `isKeyFailureHttpCode 只对鉴权配额类返回 true`() {
        assertTrue(isKeyFailureHttpCode(401))
        assertTrue(isKeyFailureHttpCode(402))
        assertTrue(isKeyFailureHttpCode(403))
        assertTrue(isKeyFailureHttpCode(429))
        assertTrue(isKeyFailureHttpCode(432))
        assertTrue(!isKeyFailureHttpCode(400))
        assertTrue(!isKeyFailureHttpCode(404))
        assertTrue(!isKeyFailureHttpCode(500))
        assertTrue(!isKeyFailureHttpCode(200))
    }

    @Test
    fun `keyCooldownHoursToMillis 最小 1 小时`() {
        assertEquals(24L * 3600_000L, keyCooldownHoursToMillis(24))
        assertEquals(1L * 3600_000L, keyCooldownHoursToMillis(0))
        assertEquals(1L * 3600_000L, keyCooldownHoursToMillis(-5))
    }

    @Test
    fun `失败换 key 重试当前请求`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        val attempted = mutableListOf<String>()
        val result = withKeyRetry(
            keys = "k1 k2 k3",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { key ->
            attempted.add(key)
            if (key != "k3") throw SearchHttpException(429, "limit", "limit $key")
            "ok"
        }
        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        // k1/k2 被冷却，k3 成功
        val snap = roulette.snapshot("k1 k2 k3", "p")
        assertEquals(3, snap.total)
        assertEquals(2, snap.cooling)
        // 已尝试的不重复
        assertEquals(attempted.distinct().size, attempted.size)
    }

    @Test
    fun `非 key 失败不换 key`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        var calls = 0
        val result = withKeyRetry(
            keys = "k1 k2",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { _ ->
            calls++
            throw SearchHttpException(500, "err", "server error")
        }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
        assertEquals(0, roulette.snapshot("k1 k2", "p").cooling)
    }

    @Test
    fun `网络错误不换 key`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        var calls = 0
        val result: Result<String> = withKeyRetry(
            keys = "k1 k2",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { _ ->
            calls++
            throw java.io.IOException("network down")
        }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `单 key 不重复尝试`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        var calls = 0
        val result: Result<String> = withKeyRetry(
            keys = "only",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { _ ->
            calls++
            throw SearchHttpException(429, "limit", "limit")
        }
        assertTrue(result.isFailure)
        assertEquals(1, calls)
        assertEquals(1, roulette.snapshot("only", "p").cooling)
    }

    @Test
    fun `全部冷却直接失败不执行请求`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        roulette.reportFailure("k1", "p", 60_000L)
        roulette.reportFailure("k2", "p", 60_000L)
        var calls = 0
        val result: Result<String> = withKeyRetry(
            keys = "k1 k2",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { _ ->
            calls++
            "ok"
        }
        assertTrue(result.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun `成功清除冷却`() = runBlocking {
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage())
        roulette.reportFailure("k1", "p", 60_000L)
        assertEquals(1, roulette.snapshot("k1 k2", "p").cooling)
        // k1 冷却中，选到 k2 并成功
        val result = withKeyRetry(
            keys = "k1 k2",
            providerId = "p",
            cooldownMillis = 60_000L,
            roulette = roulette,
        ) { key ->
            // 确保没选到冷却中的 k1
            assertTrue(key == "k2")
            "ok"
        }
        assertTrue(result.isSuccess)
    }
}
