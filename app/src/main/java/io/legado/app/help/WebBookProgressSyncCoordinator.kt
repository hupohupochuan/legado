package io.legado.app.help

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Serializes Web progress pull/save/upload operations for the same book. */
object WebBookProgressSyncCoordinator {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withBook(name: String, author: String, block: suspend () -> T): T {
        val key = "$name\u0000$author"
        return locks.getOrPut(key) { Mutex() }.withLock { block() }
    }
}
