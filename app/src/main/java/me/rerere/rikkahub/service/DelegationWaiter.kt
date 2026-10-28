package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.data.ai.tools.local.DelegateAssistantException
import java.util.concurrent.atomic.AtomicReference

internal class DelegationWaiter {
    private val deferred = CompletableDeferred<String?>()
    private val terminalError = AtomicReference<String?>(null)

    val terminalErrorCode: String? get() = terminalError.get()
    val deferred: CompletableDeferred<String?> get() = deferred

    suspend fun await(): String? = deferred.await()

    fun succeed(value: String): Boolean {
        if (terminalError.get() != null) return false
        return deferred.complete(value)
    }

    fun fail(code: String): Boolean {
        if (!terminalError.compareAndSet(null, code)) return false
        return deferred.completeExceptionally(DelegateAssistantException(code))
    }
}
