package io.legado.app.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.LogRecord

class LogUtilsTest {

    @Before
    fun setUp() {
        LogFileWriter.stop()
    }

    @After
    fun tearDown() {
        LogFileWriter.stop()
    }

    @Test
    fun `LogUtils Status enum has all expected values`() {
        val values = LogUtils.Status.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(LogUtils.Status.UNINITIALIZED))
        assertTrue(values.contains(LogUtils.Status.INITIALIZING))
        assertTrue(values.contains(LogUtils.Status.READY))
        assertTrue(values.contains(LogUtils.Status.FAILED))
    }

    @Test
    fun `LogFileWriter execute runs serially without blocking caller`() {
        LogFileWriter.start()
        val executionLog = mutableListOf<String>()
        val latch = CountDownLatch(3)
        val slowDone = CountDownLatch(1)

        LogFileWriter.execute {
            Thread.sleep(100)
            synchronized(executionLog) { executionLog.add("slow") }
            slowDone.countDown()
            latch.countDown()
        }

        LogFileWriter.execute {
            synchronized(executionLog) { executionLog.add("fast1") }
            latch.countDown()
        }

        LogFileWriter.execute {
            synchronized(executionLog) { executionLog.add("fast2") }
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("slow", "fast1", "fast2"), executionLog)
    }

    @Test
    fun `LogFileWriter flushAndAwait ensures all prior work is flushed`() {
        LogFileWriter.start()
        val maxOrder = AtomicInteger(0)

        for (i in 1..50) {
            val order = i
            LogFileWriter.execute {
                maxOrder.let { cur ->
                    while (true) {
                        val prev = cur.get()
                        if (order <= prev) break
                        if (cur.compareAndSet(prev, order)) break
                    }
                }
            }
        }

        LogFileWriter.flushAndAwait(5000L)
        assertEquals(50, maxOrder.get())
    }

    @Test
    fun `LogFileWriter does not block reading threads`() {
        LogFileWriter.start()
        val readerDone = CountDownLatch(1)
        val logDone = CountDownLatch(1)

        LogFileWriter.execute {
            Thread.sleep(300)
            logDone.countDown()
        }

        val readerThread = Thread {
            readerDone.countDown()
        }
        readerThread.start()

        assertTrue("Reader thread should not be blocked", readerDone.await(1, TimeUnit.SECONDS))
        assertTrue(logDone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `LogFileWriter stop prevents further execution`() {
        LogFileWriter.start()
        val executed = AtomicBoolean(false)
        LogFileWriter.execute { Thread.sleep(100) }
        LogFileWriter.flushAndAwait(5000L)
        LogFileWriter.stop()

        LogFileWriter.execute { executed.set(true) }

        Thread.sleep(200)
        assertFalse("Task should not execute after stop", executed.get())
    }

    @Test
    fun `multiple start calls do not reset counter`() {
        LogFileWriter.start()
        for (i in 1..5) {
            LogFileWriter.execute { }
        }
        LogFileWriter.flushAndAwait(5000L)
        LogFileWriter.start()
        assertEquals(0, LogFileWriter.droppedLogCount)
    }

    @Test
    fun `flushAndAwait with timeout returns within time limit`() {
        LogFileWriter.start()
        val start = System.currentTimeMillis()
        LogFileWriter.flushAndAwait(100L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Should return quickly when queue is empty", elapsed < 500)
    }

    @Test
    fun `flushAndAwait correctly chains multiple flushes`() {
        LogFileWriter.start()
        val results = mutableListOf<Int>()
        val latch = CountDownLatch(1)

        for (i in 1..5) {
            LogFileWriter.execute {
                synchronized(results) { results.add(i) }
            }
        }
        LogFileWriter.execute { latch.countDown() }

        latch.await(5, TimeUnit.SECONDS)
        LogFileWriter.flushAndAwait(5000L)

        assertEquals(listOf(1, 2, 3, 4, 5), results)
    }
}
