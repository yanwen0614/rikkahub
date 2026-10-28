package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.ai.tools.local.DelegateAssistantException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationWaiterTest {
    @Test
    fun `approval and deletion errors win once`() = runTest {
        listOf("target_approval_required", "target_conversation_deleted").forEach { code ->
            val waiter = DelegationWaiter()
            assertTrue(waiter.fail(code))
            assertFalse(waiter.fail("target_generation_failed"))
            assertFalse(waiter.succeed("late result"))
            val failure = runCatching { waiter.await() }.exceptionOrNull()
            assertTrue(failure is DelegateAssistantException)
            assertEquals(code, (failure as DelegateAssistantException).code)
        }
    }

    @Test
    fun `source waiter cancellation does not cancel independent target job`() = runTest {
        val waiter = DelegationWaiter()
        val targetJob = async { awaitCancellation() }
        val sourceWaiter = async { waiter.await() }
        sourceWaiter.cancel(CancellationException("source cancelled"))
        assertTrue(targetJob.isActive)
        targetJob.cancel()
    }
}
