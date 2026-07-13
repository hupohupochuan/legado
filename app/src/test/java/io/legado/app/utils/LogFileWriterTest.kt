package io.legado.app.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LogFileWriterTest {

    @Before
    fun setUp() {
        LogFileWriter.stop()
    }

    @After
    fun tearDown() {
        LogFileWriter.stop()
    }

    @Test
    fun `start and stop are idempotent`() {
        LogFileWriter.start()
        LogFileWriter.start()
        LogFileWriter.stop()
        LogFileWriter.stop()
    }

    @Test
    fun `execute runs tasks on dedicated thread`() {
        LogFileWriter.start()
        val threadNames = mutableListOf<String>()
        val latch = CountDownLatch(2)

        LogFileWriter.execute {
            threadNames.add(Thread.currentThread().name)
            latch.countDown()
        }
        LogFileWriter.execute {
            threadNames.add(Thread.currentThread().name)
            latch.countDown()
        }

        assertTrue("Tasks should complete within timeout", latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, threadNames.size)
        assertTrue("All tasks should run on LogWriter thread",
            threadNames.all { it == "Legado-LogWriter" })
    }

    @Test
    fun `execute preserves ordering`() {
        LogFileWriter.start()
        val results = mutableListOf<Int>()
        val latch = CountDownLatch(5)

        for (i in 1..5) {
            LogFileWriter.execute {
                synchronized(results) {
                    results.add(i)
                }
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3, 4, 5), results)
    }

    @Test
    fun `flushAndAwait returns true after tasks complete`() {
        LogFileWriter.start()
        val counter = AtomicInteger(0)

        for (i in 1..10) {
            LogFileWriter.execute { counter.incrementAndGet() }
        }

        val result = LogFileWriter.flushAndAwait(5000L)
        assertTrue("flushAndAwait should return true", result)
        assertEquals(10, counter.get())
    }

    @Test
    fun `flushAndAwait returns true when executor is shut down`() {
        LogFileWriter.stop()
        val result = LogFileWriter.flushAndAwait(1000L)
        assertTrue("Should return true when stopped", result)
    }

    @Test
    fun `flushAndAwait returns true when not started`() {
        val result = LogFileWriter.flushAndAwait(1000L)
        assertTrue("Should return true when never started", result)
    }

    @Test
    fun `discardCount increments when executor is stopped`() {
        LogFileWriter.start()
        LogFileWriter.stop()

        LogFileWriter.execute { }

        assertEquals(1, LogFileWriter.droppedLogCount)
    }

    @Test
    fun `discardCount resets on start`() {
        LogFileWriter.start()
        LogFileWriter.execute { }
        LogFileWriter.flushAndAwait(5000L)
        LogFileWriter.stop()

        LogFileWriter.execute { }
        assertEquals(1, LogFileWriter.droppedLogCount)

        LogFileWriter.start()
        assertEquals(0, LogFileWriter.droppedLogCount)
    }

    @Test
    fun `tasks run independently of globalExecutor`() {
        LogFileWriter.start()
        val logWriterThread = AtomicReference<Thread>()
        val latch = CountDownLatch(1)

        LogFileWriter.execute {
            logWriterThread.set(Thread.currentThread())
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("Should not be on the caller thread", logWriterThread.get() !== Thread.currentThread())
    }

    @Test
    fun `flushAndAwait blocks until previous tasks finish`() {
        LogFileWriter.start()
        val order = mutableListOf<Int>()
        val barrier = CountDownLatch(1)

        LogFileWriter.execute {
            barrier.await(5, TimeUnit.SECONDS)
            synchronized(order) { order.add(1) }
        }

        for (i in 2..5) {
            LogFileWriter.execute {
                synchronized(order) { order.add(i) }
            }
        }

        barrier.countDown()
        LogFileWriter.flushAndAwait(5000L)

        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun `high frequency enqueue does not lose tasks`() {
        LogFileWriter.start()
        val counter = AtomicInteger(0)
        val count = 1000

        for (i in 1..count) {
            LogFileWriter.execute { counter.incrementAndGet() }
        }

        LogFileWriter.flushAndAwait(10000L)
        assertEquals(count, counter.get())
    }
}
