package io.legado.app.utils

import java.io.InputStream
import java.util.Scanner

fun InputStream?.isJson(): Boolean {
    this ?: return false
    return runCatching {
        this.use { stream ->
            // 读取开头最多 128 字节，避免依赖 available()
            val head = ByteArray(128)
            val headRead = stream.read(head)
            if (headRead <= 0) return false
            val headStr = String(head, 0, headRead, Charsets.UTF_8).trim()

            // 顺序读取剩余内容，仅保留最后 128 字节
            val tailBytes = readTailBytes(stream, 128)
            val tailStr = String(tailBytes, Charsets.UTF_8).trim()

            (headStr + tailStr).isJson()
        }
    }.getOrDefault(false)
}

/**
 * 顺序读取流并保留最后 [maxLen] 字节。
 * 不依赖 [InputStream.available] 或 [InputStream.skip]。
 */
private fun readTailBytes(stream: InputStream, maxLen: Int): ByteArray {
    val tail = ByteArray(maxLen)
    val buffer = ByteArray(8192)
    var total = 0
    var read: Int
    while (stream.read(buffer).also { read = it } != -1) {
        if (read > 0) {
            if (total + read <= maxLen) {
                System.arraycopy(buffer, 0, tail, total, read)
            } else {
                if (read >= maxLen) {
                    System.arraycopy(buffer, read - maxLen, tail, 0, maxLen)
                } else {
                    val keep = maxLen - read
                    System.arraycopy(tail, total - keep, tail, 0, keep)
                    System.arraycopy(buffer, 0, tail, keep, read)
                }
            }
            total += read
        }
    }
    return if (total <= maxLen) tail.copyOf(total) else tail
}

fun InputStream?.contains(str: String): Boolean {
    this ?: return false
    this.use {
        val scanner = Scanner(it)
        return scanner.findWithinHorizon(str, 0) != null
    }
}
