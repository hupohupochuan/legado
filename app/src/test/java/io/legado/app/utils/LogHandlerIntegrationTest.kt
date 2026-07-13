package io.legado.app.utils

import io.legado.app.utils.compress.ZipUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.zip.ZipFile

class LogHandlerIntegrationTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("log-test", "").apply {
            delete()
            mkdirs()
        }
        LogFileWriter.stop()
        LogUtils.prepare(false)
    }

    @After
    fun tearDown() {
        LogUtils.prepare(false)
        LogFileWriter.stop()
        tempDir.deleteRecursively()
    }

    private fun createHandler(name: String = "test.log"): Pair<AsyncFileHandler, File> {
        val logFile = File(tempDir, name)
        val handler = AsyncFileHandler(logFile.absolutePath)
        handler.formatter = SimpleFormatter()
        handler.level = Level.ALL
        return handler to logFile
    }

    @Test
    fun `AsyncFileHandler writes to file asynchronously`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler()

        handler.publish(LogRecord(Level.INFO, "hello file"))

        val flushed = LogFileWriter.flushAndAwait(5000L) { handler.flush() }
        assertTrue("Flush should complete", flushed)
        assertTrue("Log file should exist", logFile.exists())
        assertTrue("Log file should contain message", logFile.readText().contains("hello file"))

        handler.close()
    }

    @Test
    fun `flushAndAwait with flushAction really flushes FileHandler`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler()

        handler.publish(LogRecord(Level.INFO, "before flush"))

        val flushed = LogFileWriter.flushAndAwait(5000L) { handler.flush() }
        assertTrue(flushed)

        val content = logFile.readText()
        assertTrue(content.contains("before flush"))
        handler.close()
    }

    @Test
    fun `switching handler off after enqueue should not drop flushed records`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler()

        handler.publish(LogRecord(Level.INFO, "queued record"))

        // Simulate flush then switch off
        LogFileWriter.flushAndAwait(5000L) { handler.flush() }
        handler.level = Level.OFF

        assertTrue("Record should be on disk after flush", logFile.readText().contains("queued record"))
        handler.close()
    }

    @Test
    fun `LogUtils d keeps sending to system Logger`() {
        val testLogger = Logger.getLogger("Legado")
        val captured = mutableListOf<String>()
        val capturingHandler = object : java.util.logging.Handler() {
            override fun publish(record: LogRecord?) {
                record?.message?.let { captured.add(it) }
            }
            override fun flush() {}
            override fun close() {}
        }
        testLogger.addHandler(capturingHandler)
        testLogger.level = Level.ALL

        try {
            LogUtils.d("TestTag", "system logcat message")
            assertTrue("System logger should receive message",
                captured.any { it.contains("system logcat message") })
        } finally {
            testLogger.removeHandler(capturingHandler)
        }
    }

    @Test
    fun `one LogUtils d call writes marker to file exactly once`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler("log-utils-once.log")
        val fileHandlerField = LogUtils::class.java.getDeclaredField("fileHandler").apply {
            isAccessible = true
        }
        val statusField = LogUtils::class.java.getDeclaredField("status").apply {
            isAccessible = true
        }
        val marker = "single-write-${System.nanoTime()}"

        LogUtils.logger.addHandler(handler)
        fileHandlerField.set(LogUtils, handler)
        statusField.set(LogUtils, LogUtils.Status.READY)
        try {
            LogUtils.d("IntegrationTest", marker)
            assertTrue(LogUtils.flushAndAwait(5000L))
            assertEquals(1, Regex(Regex.escape(marker)).findAll(logFile.readText()).count())
        } finally {
            fileHandlerField.set(LogUtils, null)
            statusField.set(LogUtils, LogUtils.Status.UNINITIALIZED)
            LogUtils.logger.removeHandler(handler)
            handler.close()
        }
    }

    @Test
    fun `export snapshot zip contains appLog from actual log directory`() {
        val actualLogDir = File(tempDir, "logs").apply { mkdirs() }
        File(actualLogDir, "appLog-test.txt").writeText("export marker")
        val snapshotDir = File(tempDir, "log-export").apply { mkdirs() }

        val result = LogExportUtils.createAppLogSnapshot(
            snapshotDir = snapshotDir,
            logSrcDir = actualLogDir,
            flushAndRun = { action -> action() }
        )
        val zipFile = File(tempDir, "logs.zip")
        assertTrue(ZipUtils.zipFiles(listOf(File(snapshotDir, "logs")), zipFile))

        assertEquals(1, result.appLogCount)
        assertTrue(result.flushOk)
        ZipFile(zipFile).use { zip ->
            assertNotNull(zip.getEntry("logs/appLog-test.txt"))
        }
    }

    @Test
    fun `missing app log directory is not reported as successful flush`() {
        val result = LogExportUtils.createAppLogSnapshot(
            snapshotDir = File(tempDir, "log-export"),
            logSrcDir = File(tempDir, "missing"),
            flushAndRun = { action -> action() }
        )

        assertEquals(0, result.appLogCount)
        assertFalse(result.flushOk)
    }

    @Test
    fun `logging while disabled does not fill pre-init queue`() {
        val queueField = LogUtils::class.java.getDeclaredField("preInitQueue").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(LogUtils) as java.util.Queue<LogRecord>
        queue.clear()

        LogUtils.prepare(false)
        LogUtils.d("IntegrationTest", "disabled marker")

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `LogFileWriter serializes multiple file handler publishes`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler()
        val count = 50

        for (i in 1..count) {
            handler.publish(LogRecord(Level.INFO, "line $i"))
        }

        val flushed = LogFileWriter.flushAndAwait(5000L) { handler.flush() }
        assertTrue(flushed)

        val text = logFile.readText()
        for (i in 1..count) {
            assertTrue("Missing line $i", text.contains("line $i"))
        }
        handler.close()
    }

    @Test
    fun `LogFileWriter flushAndAwait action runs on log thread`() {
        LogFileWriter.start()
        val threadName = java.util.concurrent.atomic.AtomicReference<String>()
        val latch = CountDownLatch(1)

        LogFileWriter.flushAndAwait(5000L) {
            threadName.set(Thread.currentThread().name)
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("Legado-LogWriter", threadName.get())
    }

    @Test
    fun `closing handler after flush releases lock file`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler()

        handler.publish(LogRecord(Level.INFO, "x"))
        LogFileWriter.flushAndAwait(5000L) { handler.flush() }
        handler.close()

        val lockFile = File(tempDir, "test.log.lck")
        assertFalse("Lock file should be removed after close", lockFile.exists())
    }

    @Test
    fun `rejected tasks increment discard count`() {
        LogFileWriter.start()
        LogFileWriter.stop()
        val before = LogFileWriter.droppedLogCount
        LogFileWriter.execute { }
        assertEquals(before + 1, LogFileWriter.droppedLogCount)
    }

    @Test
    fun `concurrent LogFileWriter start does not create multiple executors`() {
        val threads = (1..10).map {
            Thread { LogFileWriter.start() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(2000) }

        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(20)
        repeat(20) {
            LogFileWriter.execute {
                active.incrementAndGet()
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(20, active.get())
    }

    @Test
    fun `LogUtils withLogThreadFlush executes action after flush`() {
        LogFileWriter.start()
        val (handler, logFile) = createHandler("with-flush.log")

        // Publish a record to a handler directly (simulating what LogUtils does internally)
        handler.publish(LogRecord(Level.INFO, "internal record"))

        val result = LogUtils.withLogThreadFlush(5000L) {
            handler.flush()
            logFile.readText()
        }

        assertNotNull(result)
        assertTrue(result!!.contains("internal record"))
        handler.close()
    }
}
