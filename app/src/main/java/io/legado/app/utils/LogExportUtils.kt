package io.legado.app.utils

import java.io.File

object LogExportUtils {

    data class SnapshotResult(
        val appLogCount: Int,
        val flushOk: Boolean
    )

    /**
     * Flushes the active handler and copies a stable application-log snapshot.
     * [logSrcDir] is already the actual logs directory and must not have "logs" appended again.
     */
    fun createAppLogSnapshot(
        snapshotDir: File,
        logSrcDir: File?,
        flushAndRun: ((() -> Int) -> Int?)
    ): SnapshotResult {
        if (logSrcDir == null || !logSrcDir.isDirectory) {
            return SnapshotResult(appLogCount = 0, flushOk = false)
        }
        val count = flushAndRun {
            val snapshotLogs = File(snapshotDir, "logs").apply { mkdirs() }
            var copied = 0
            logSrcDir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".lck")
            }?.forEach { src ->
                src.copyTo(File(snapshotLogs, src.name), overwrite = true)
                copied++
            }
            copied
        }
        return SnapshotResult(
            appLogCount = count ?: 0,
            flushOk = count != null
        )
    }
}
