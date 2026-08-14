package io.legado.app.model.fileBook

import io.legado.app.help.BookProgressIdentity
import java.io.InputStream
import java.security.MessageDigest

/**
 * 本地书身份由可读文件定位符（URI 或路径）与内容 SHA-1 共同生成。
 *
 * SHA-1 只用于区分本地文件，不承担密码学安全用途。最终值带算法前缀，便于未来升级算法时兼容。
 */
object LocalBookIdentity {

    private const val ALGORITHM = "SHA-1"
    private const val PREFIX = "sha1:"
    private const val BUFFER_SIZE = 8192

    data class Identities(
        val localFileKey: String,
        val bookProgressKey: String
    )

    fun create(bookUrl: String, inputStream: InputStream): String =
        createIdentities(bookUrl, inputStream).localFileKey

    fun createIdentities(bookUrl: String, inputStream: InputStream): Identities {
        val contentDigest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) break
            if (read > 0) contentDigest.update(buffer, 0, read)
        }

        val contentSha1 = contentDigest.digest()
        val identityDigest = MessageDigest.getInstance(ALGORITHM).apply {
            update(bookUrl.toByteArray(Charsets.UTF_8))
            update(0)
            update(contentSha1)
        }
        return Identities(
            localFileKey = PREFIX + identityDigest.digest().toHex(),
            bookProgressKey = BookProgressIdentity.fromContentSha1(contentSha1)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
