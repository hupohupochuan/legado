package io.legado.app.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 独立单线程日志写入器，不与阅读、图片回收等业务共用 globalExecutor。
 * 所有文件写入严格串行，保持日志顺序。
 */
object LogFileWriter {

    private val executorRef = AtomicReference<ExecutorService?>(null)
    private val discardCount = AtomicLong(0)

    val droppedLogCount: Long get() = discardCount.get()

    /**
     * 启动独立日志写入线程。幂等：若已启动则不重复创建。
     */
    @Synchronized
    fun start() {
        val current = executorRef.get()
        if (current != null && !current.isShutdown) return
        discardCount.set(0)
        executorRef.set(Executors.newSingleThreadExecutor { r ->
            Thread(r, "Legado-LogWriter").apply { isDaemon = true }
        })
    }

    /**
     * 停止接收新任务，已入队任务继续执行。
     */
    @Synchronized
    fun stop() {
        executorRef.getAndSet(null)?.shutdown()
    }

    /**
     * 提交异步写入任务。
     */
    fun execute(task: Runnable) {
        val ex = executorRef.get()
        if (ex == null || ex.isShutdown) {
            discardCount.incrementAndGet()
            return
        }
        try {
            ex.execute(task)
        } catch (_: RejectedExecutionException) {
            discardCount.incrementAndGet()
        }
    }

    /**
     * 等待之前入队的所有任务执行完毕，并在日志线程中同步执行 [flushAction]。
     * 超时返回 false。
     */
    fun flushAndAwait(timeoutMillis: Long = 5000L, flushAction: (() -> Unit)? = null): Boolean {
        val ex = executorRef.get()
        if (ex == null || ex.isShutdown) {
            flushAction?.invoke()
            return true
        }
        val latch = CountDownLatch(1)
        try {
            ex.execute {
                try {
                    flushAction?.invoke()
                } finally {
                    latch.countDown()
                }
            }
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            flushAction?.invoke()
            return true
        }
    }
}
