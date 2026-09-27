package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRouletteTest {
    @Test
    fun `splitApiKeys 按空格逗号换行切分并去重`() {
        assertEquals(listOf("a", "b", "c"), splitApiKeys("a b,c\nc  a"))
        assertEquals(emptyList<String>(), splitApiKeys("  ,\n "))
    }

    @Test
    fun `lru 按顺序轮询`() {
        var now = 1000L
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage(), clock = { now })
        val keys = "k1 k2 k3"
        // 首次三个 key 依次被选中
        assertEquals("k1", roulette.next(keys, "p"))
        now += 10
        assertEquals("k2", roulette.next(keys, "p"))
        now += 10
        assertEquals("k3", roulette.next(keys, "p"))
        now += 10
        // 一轮过后回到最久未使用的 k1
        assertEquals("k1", roulette.next(keys, "p"))
    }

    @Test
    fun `reportFailure 后跳过冷却 key`() {
        var now = 1000L
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage(), clock = { now })
        val keys = "k1 k2"
        assertEquals("k1", roulette.next(keys, "p"))
        roulette.reportFailure("k1", "p", 60_000L)
        // k1 冷却中，只能选 k2
        assertEquals("k2", roulette.nextExcluding(keys, "p", emptySet()))
        // 排除 k2 后无可用
        assertNull(roulette.nextExcluding(keys, "p", setOf("k2")))
        val snap = roulette.snapshot(keys, "p")
        assertEquals(2, snap.total)
        assertEquals(1, snap.cooling)
        assertEquals(1000L + 60_000L, snap.nextRecoveryAtMillis)
    }

    @Test
    fun `冷却过期后恢复可用`() {
        var now = 0L
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage(), clock = { now })
        roulette.reportFailure("k1", "p", 1000L)
        now = 500L
        assertNull(roulette.nextExcluding("k1", "p", emptySet()))
        now = 1001L
        assertEquals("k1", roulette.nextExcluding("k1", "p", emptySet()))
    }

    @Test
    fun `reportSuccess 清除冷却`() {
        var now = 0L
        // 注意：reportFailure 的 key 参数是单个 key
        val r2 = KeyRoulette.lru(InMemoryKeyPoolStorage(), clock = { now })
        r2.reportFailure("k1", "p", 60_000L)
        assertEquals(1, r2.snapshot("k1 k2", "p").cooling)
        r2.reportSuccess("k1", "p")
        assertEquals(0, r2.snapshot("k1 k2", "p").cooling)
    }

    @Test
    fun `不在 key 列表中的冷却条目被过滤`() {
        var now = 0L
        val storage = InMemoryKeyPoolStorage()
        val roulette = KeyRoulette.lru(storage, clock = { now })
        roulette.reportFailure("old-key", "p", 60_000L)
        // 快照只统计当前列表中的 key
        assertEquals(0, roulette.snapshot("k1 k2", "p").cooling)
        assertEquals(2, roulette.snapshot("k1 k2", "p").total)
    }

    @Test
    fun `单 key 时 next 仍返回该 key`() {
        var now = 0L
        val roulette = KeyRoulette.lru(InMemoryKeyPoolStorage(), clock = { now })
        assertEquals("only", roulette.next("only", "p"))
        roulette.reportFailure("only", "p", 60_000L)
        // next 保持兼容：全部冷却时回退，不返回空
        assertEquals("only", roulette.next("only", "p"))
        // 但新接口返回 null，调用方不再重复尝试
        assertNull(roulette.nextExcluding("only", "p", emptySet()))
        assertTrue(roulette.snapshot("only", "p").cooling == 1)
    }

    @Test
    fun `默认实现快照与排除`() {
        val roulette = KeyRoulette.default()
        assertEquals(2, roulette.snapshot("a b", "p").total)
        assertEquals(0, roulette.snapshot("a b", "p").cooling)
        assertNull(roulette.nextExcluding("a", "p", setOf("a")))
    }
}
