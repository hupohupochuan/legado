package io.legado.app.utils

import java.util.logging.FileHandler
import java.util.logging.LogRecord

class AsyncFileHandler(pattern: String) : FileHandler(pattern) {

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        LogFileWriter.execute {
            super.publish(record)
        }
    }

}
