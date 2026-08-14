package io.legado.app.help

import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebBookProgressUploadScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val uploadDelayMillis: Long = 60_000L,
    private val uploader: suspend (BookProgress) -> Boolean = { progress ->
        var uploaded = false
        WebBookProgressSyncCoordinator.withBook(progress.syncKey) {
            BookProgressSyncProvider.current.uploadBookProgress(progress) {
                uploaded = true
            }
        }
        uploaded
    }
) {

    private data class UploadState(
        var pending: BookProgress? = null,
        var lastUploadedKey: String? = null,
        var timer: Job? = null,
        var uploadJob: Job? = null,
        var changedWhileUploading: Boolean = false,
        var lastAttemptSucceeded: Boolean? = null
    )

    private val mutex = Mutex()
    private val states = mutableMapOf<String, UploadState>()

    suspend fun enqueue(progress: BookProgress) {
        mutex.withLock {
            val state = states.getOrPut(bookKey(progress)) { UploadState() }
            val key = progressKey(progress)
            if (key == state.lastUploadedKey && state.uploadJob == null) {
                state.pending = null
                state.timer?.cancel()
                state.timer = null
                return
            }
            state.pending = progress
            if (state.uploadJob != null) {
                state.changedWhileUploading = true
            } else if (state.timer == null) {
                scheduleLocked(state)
            }
        }
    }

    /** Re-arms a previously failed dirty upload when the book is opened again. */
    suspend fun retryOnOpen(progress: BookProgress) {
        mutex.withLock {
            val state = states[bookKey(progress)] ?: return
            if (state.pending != null && state.timer == null && state.uploadJob == null) {
                scheduleLocked(state)
            }
        }
    }

    suspend fun flush(progress: BookProgress) {
        val key = bookKey(progress)
        while (true) {
            val activeJob = mutex.withLock {
                val state = states.getOrPut(key) { UploadState() }
                state.timer?.cancel()
                state.timer = null
                state.uploadJob ?: startUploadLocked(state)
            } ?: return
            activeJob.join()
            val shouldContinue = mutex.withLock {
                val state = states[key] ?: return@withLock false
                state.lastAttemptSucceeded == true && state.pending != null
            }
            if (!shouldContinue) return
        }
    }

    private fun scheduleLocked(state: UploadState) {
        state.timer = scope.launch {
            delay(uploadDelayMillis)
            mutex.withLock {
                state.timer = null
                startUploadLocked(state)
            }
        }
    }

    private fun startUploadLocked(state: UploadState): Job? {
        val progress = state.pending ?: return null
        state.pending = null
        state.changedWhileUploading = false
        state.lastAttemptSucceeded = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val succeeded = runCatching { uploader(progress) }.getOrDefault(false)
            mutex.withLock {
                state.uploadJob = null
                state.lastAttemptSucceeded = succeeded
                if (succeeded) {
                    state.lastUploadedKey = progressKey(progress)
                } else if (state.pending == null) {
                    state.pending = progress
                }
                val pending = state.pending
                if (pending != null && progressKey(pending) == state.lastUploadedKey) {
                    state.pending = null
                }
                if (state.pending != null && state.changedWhileUploading && state.timer == null) {
                    scheduleLocked(state)
                }
            }
        }
        state.uploadJob = job
        job.start()
        return job
    }

    private fun bookKey(progress: BookProgress) = progress.syncKey

    internal fun progressKey(progress: BookProgress) = listOf(
        progress.syncKey,
        progress.durChapterIndex,
        progress.readChapterPos,
        progress.durChapterTitle
    ).joinToString("\u0000")

    companion object {
        val shared = WebBookProgressUploadScheduler()
    }
}
