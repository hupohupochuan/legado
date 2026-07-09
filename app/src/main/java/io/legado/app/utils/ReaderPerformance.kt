package io.legado.app.utils

import android.os.SystemClock
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import java.util.Locale

object ReaderPerformance {

    private const val TAG = "ReaderPerformance"
    private const val NANOS_PER_MILLIS = 1_000_000.0

    val enabled: Boolean
        get() = BuildConfig.DEBUG || AppConfig.recordLog

    fun now(): Long {
        return if (enabled) SystemClock.elapsedRealtimeNanos() else 0L
    }

    inline fun <T> trace(
        name: String,
        thresholdMs: Long,
        extra: String? = null,
        block: () -> T
    ): T {
        if (!enabled) return block()
        val start = now()
        return try {
            block()
        } finally {
            logElapsed(name, start, thresholdMs, extra)
        }
    }

    fun logElapsed(
        name: String,
        startNanos: Long,
        thresholdMs: Long,
        extra: String? = null
    ) {
        if (!enabled || startNanos == 0L) return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / NANOS_PER_MILLIS
        if (elapsedMs < thresholdMs) return
        val message = buildString {
            append(name)
            append(' ')
            append(String.format(Locale.US, "%.1fms", elapsedMs))
            if (!extra.isNullOrBlank()) {
                append(" (")
                append(extra)
                append(')')
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        if (AppConfig.recordLog) {
            AppLog.putDebug("阅读性能 $message")
        }
    }
}
