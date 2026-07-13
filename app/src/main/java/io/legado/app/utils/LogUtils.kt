package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

@SuppressLint("SimpleDateFormat")
object LogUtils {
    const val TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"
    val logTimeFormat by lazy { SimpleDateFormat(TIME_PATTERN) }

    enum class Status {
        UNINITIALIZED, INITIALIZING, READY, FAILED
    }

    @Volatile
    var status: Status = Status.UNINITIALIZED
        private set

    @Volatile
    var logDir: File? = null
        private set

    @Volatile
    var lastInitError: String? = null
        private set

    @Volatile
    var initTime: Long = 0L
        private set

    @Volatile
    private var fileHandler: AsyncFileHandler? = null

    @Volatile
    private var recordingRequested = false

    private val switchGeneration = AtomicLong(0L)

    private val preInitQueue = ConcurrentLinkedQueue<LogRecord>()
    private const val MAX_PRE_INIT_BUFFER = 500

    val logger: Logger by lazy {
        Logger.getLogger("Legado")
    }

    /**
     * Records the switch state without touching the file system.
     * Call before scheduling background initialization so early logs follow the switch semantics.
     */
    fun prepare(enabled: Boolean): Long {
        recordingRequested = enabled
        if (!enabled) {
            preInitQueue.clear()
        }
        return switchGeneration.incrementAndGet()
    }

    /**
     * 初始化日志模块。幂等：重复调用不会叠加 handler。
     * 线程安全，可在后台协程中调用。
     */
    @Synchronized
    fun init(context: Context) {
        if (status == Status.INITIALIZING) return
        if (status == Status.READY && fileHandler != null) return
        status = Status.INITIALIZING
        lastInitError = null
        try {
            closeOldHandlerLocked()
            val handler = createFileHandler(context)
            if (handler != null) {
                fileHandler = handler
                logger.addHandler(handler)
                status = Status.READY
                lastInitError = null
                initTime = System.currentTimeMillis()
                flushPreInitQueue()
                d("LogUtils", "日志模块初始化完成，目录: ${logDir?.absolutePath}")
            } else {
                status = Status.FAILED
                if (lastInitError == null) {
                    lastInitError = "无法创建日志文件"
                }
                AppLog.putNotSave("日志初始化失败: $lastInitError")
            }
        } catch (e: Exception) {
            status = Status.FAILED
            lastInitError = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.putNotSave("日志初始化异常\n$lastInitError", e)
            e.printStackTrace()
        }
    }

    /**
     * 开关日志。
     * 开启时若 handler 不存在则立即重新初始化；
     * 关闭时先 flush 再设置 level 为 OFF，避免丢弃已入队记录。
     */
    fun setEnabled(enabled: Boolean) {
        applyPreparedState(enabled, prepare(enabled))
    }

    /** Applies file-system changes for the latest switch request on a background thread. */
    @Synchronized
    fun applyPreparedState(enabled: Boolean, generation: Long): Boolean {
        if (generation != switchGeneration.get()) return false
        if (enabled) {
            if (fileHandler == null || status != Status.READY) {
                init(appCtx)
            }
            if (generation != switchGeneration.get()) return false
            updateHandlerLevelLocked(enabled)
            LogFileWriter.start()
        } else {
            flushAndAwait(5000L)
            if (generation != switchGeneration.get()) return false
            updateHandlerLevelLocked(enabled)
            preInitQueue.clear()
        }
        return true
    }

    /**
     * 安全关闭旧 handler 并清理旧锁文件。
     * 调用方必须持有对象锁。
     */
    private fun closeOldHandlerLocked() {
        val old = fileHandler
        if (old != null) {
            LogFileWriter.flushAndAwait(2000L) {
                try { old.flush() } catch (_: Exception) {}
            }
            try {
                old.close()
            } catch (_: Exception) {
            }
            fileHandler = null
            logger.removeHandler(old)
        }
        LogFileWriter.start()
        cleanOldLockFiles()
    }

    private fun createFileHandler(context: Context): AsyncFileHandler? {
        try {
            val root = context.externalCacheDir ?: context.cacheDir
            val logFolder = FileUtils.createFolderIfNotExist(root, "logs")
            logDir = logFolder
            cleanExpiredLogs(logFolder)
            val date = getCurrentDateStr(TIME_PATTERN).replace(" ", "_").replace(":", "-")
            val logPath = FileUtils.getPath(root = logFolder, "appLog-$date.txt")
            return AsyncFileHandler(logPath).apply {
                formatter = object : Formatter() {
                    override fun format(record: LogRecord): String {
                        return getCurrentDateStr(TIME_PATTERN) + ": " + record.message + "\n"
                    }
                }
                level = if (recordingRequested) Level.INFO else Level.OFF
            }
        } catch (e: Exception) {
            lastInitError = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.putNotSave("创建fileHandler出错\n$e", e)
            e.printStackTrace()
            return null
        }
    }

    private fun cleanExpiredLogs(logFolder: File) {
        val expiredTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
        logFolder.listFiles()?.forEach {
            if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) {
                it.delete()
            }
        }
    }

    private fun cleanOldLockFiles() {
        val dir = logDir ?: return
        dir.listFiles()?.forEach {
            if (it.name.endsWith(".lck")) {
                it.delete()
            }
        }
    }

    private fun flushPreInitQueue() {
        var record = preInitQueue.poll()
        while (record != null) {
            try {
                fileHandler?.publish(record)
            } catch (_: Exception) {
            }
            record = preInitQueue.poll()
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        publish(LogRecord(Level.INFO, "$tag $msg"))
    }

    inline fun d(tag: String, lazyMsg: () -> String) {
        if (logger.isLoggable(Level.INFO)) {
            publish(LogRecord(Level.INFO, "$tag ${lazyMsg()}"))
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        publish(LogRecord(Level.WARNING, "$tag $msg"))
    }

    @PublishedApi
    internal fun publish(record: LogRecord) {
        synchronized(this) {
            // A READY handler is attached to logger, so logger.log() is the only file path.
            // Publishing the same record directly to fileHandler here would duplicate it.
            logger.log(record)
            if (fileHandler == null || status != Status.READY) {
                bufferPreInit(record)
            }
        }
    }

    private fun bufferPreInit(record: LogRecord) {
        val shouldBuffer = status == Status.INITIALIZING || recordingRequested
        if (shouldBuffer && preInitQueue.size < MAX_PRE_INIT_BUFFER) {
            preInitQueue.add(record)
        }
    }

    private fun updateHandlerLevelLocked(enabled: Boolean) {
        val level = if (enabled) Level.INFO else Level.OFF
        fileHandler?.level = level
    }

    @SuppressLint("SimpleDateFormat")
    fun getCurrentDateStr(pattern: String): String {
        val date = Date()
        val sdf = SimpleDateFormat(pattern)
        return sdf.format(date)
    }

    fun logDeviceInfo() {
        d("DeviceInfo") {
            buildString {
                kotlin.runCatching {
                    append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                    append("BRAND=").append(Build.BRAND).append("\n")
                    append("MODEL=").append(Build.MODEL).append("\n")
                    append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                    append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                    // WebSettings.getDefaultUserAgent() 会强制冷启动系统 WebView。
                    // 日志初始化与 Activity 创建并发时可能争用 WebView 全局初始化锁，
                    // 导致首次安装启动停在灰色预览窗口；这里只记录无副作用的系统 UA。
                    append("HttpUserAgent=")
                        .append(System.getProperty("http.agent").orEmpty())
                        .append("\n")
                    append("packageName=").append(appCtx.packageName).append("\n")
                    append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                    AppConst.appInfo.let {
                        append("versionName=").append(it.versionName).append("\n")
                        append("versionCode=").append(it.versionCode).append("\n")
                    }
                    append("logStatus=").append(status).append("\n")
                    append("logDir=").append(logDir?.absolutePath).append("\n")
                }
            }
        }
    }

    /**
     * 等待日志落盘（包括 FileHandler.flush()）。
     */
    fun flushAndAwait(timeoutMillis: Long = 5000L): Boolean {
        return LogFileWriter.flushAndAwait(timeoutMillis) {
            try {
                fileHandler?.flush()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 在日志线程中 flush 文件 handler，然后同步执行 [action]。
     * 返回 action 的结果，超时或失败返回 null。
     */
    fun <T> withLogThreadFlush(timeoutMillis: Long = 5000L, action: () -> T): T? {
        val ref = java.util.concurrent.atomic.AtomicReference<T>()
        val ok = LogFileWriter.flushAndAwait(timeoutMillis) {
            try {
                fileHandler?.flush()
            } catch (_: Exception) {
            }
            ref.set(action())
        }
        return if (ok) ref.get() else null
    }

    fun close() {
        flushAndAwait(2000L)
        synchronized(this) {
            val h = fileHandler
            if (h != null) {
                try {
                    h.close()
                } catch (_: Exception) {
                }
                logger.removeHandler(h)
                fileHandler = null
            }
            LogFileWriter.stop()
            preInitQueue.clear()
            status = Status.UNINITIALIZED
        }
    }
}

fun Throwable.printOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}
