package io.legado.app.help.coroutine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoroutineTest {

    @Test
    fun fastTaskDoesNotSkipChainedSuccessCallback() = runBlocking {
        val result = CompletableDeferred<Int>()

        Coroutine.async(
            scope = this,
            context = Dispatchers.Default,
            executeContext = Dispatchers.Default
        ) { 42 }.onSuccess {
            result.complete(it)
        }

        assertEquals(42, withTimeout(1_000) { result.await() })
    }

    @Test
    fun cancellationIsNotDeliveredAsErrorOrFinally() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        var errorCalled = false
        var finallyCalled = false
        val task = Coroutine.async(
            scope = this,
            context = Dispatchers.Unconfined,
            executeContext = Dispatchers.Unconfined
        ) { awaitCancellation() }
            .onError { errorCalled = true }
            .onFinally { finallyCalled = true }
            .onCancel(Dispatchers.Unconfined) { cancelled.complete(Unit) }

        task.cancel()
        withTimeout(1_000) { cancelled.await() }

        assertFalse(errorCalled)
        assertFalse(finallyCalled)
    }
}
