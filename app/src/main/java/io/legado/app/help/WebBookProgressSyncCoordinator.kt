package io.legado.app.help

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes Web progress pull/save/upload operations for the same book. */
object WebBookProgressSyncCoordinator {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withBook(key: String, block: suspend () -> T): T {
        return locks.getOrPut(key) { Mutex() }.withLock { block() }
    }
}
