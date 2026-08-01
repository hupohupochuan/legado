package io.legado.app.model

/** Web 阅读页单次章节计时的可信边界与秒级落盘区间。 */
internal object WebReadTimeSession {

    const val MIN_DURATION_MS = 5_000L
    const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L

    data class Range(val startSec: Long, val endSec: Long)

    /**
     * 浏览器只提供经过时间，结束时间统一取手机端时间，避免客户端时钟偏差污染日期统计。
     */
    fun fromDuration(durationMs: Long, endAtMs: Long): Range? {
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS || endAtMs < 0L) return null
        val durationSec = durationMs / 1_000L
        val endSec = endAtMs / 1_000L
        val startSec = endSec - durationSec
        return if (isValidRange(startSec, endSec)) Range(startSec, endSec) else null
    }

    fun isValidRange(startSec: Long, endSec: Long): Boolean {
        if (startSec < 0L || endSec <= startSec) return false
        val durationSec = endSec - startSec
        return durationSec in (MIN_DURATION_MS / 1_000L)..(MAX_DURATION_MS / 1_000L)
    }
}
