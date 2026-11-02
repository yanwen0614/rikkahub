package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.data.ai.tools.local.DelegateAssistantException

internal class DelegationWaiter {
    private val result = CompletableDeferred<String?>()
    private var terminalError: String? = null

    val terminalErrorCode: String? get() = synchronized(this) { terminalError }
    val deferred: CompletableDeferred<String?> get() = result

    suspend fun await(): String? = result.await()

    @Synchronized
    fun succeed(value: String): Boolean {
        if (result.isCompleted) return false
        return result.complete(value)
    }

    @Synchronized
    fun fail(code: String): Boolean {
        if (result.isCompleted) return false
        terminalError = code
        return result.completeExceptionally(DelegateAssistantException(code))
    }
}
