package io.legado.app.help

import io.legado.app.data.entities.BookProgress
import java.security.MessageDigest

/**
 * WebDAV 阅读进度使用的跨设备身份。
 *
 * 本地书优先使用原始文件内容摘要，在线书在同名冲突时使用书籍地址摘要。
 * 摘要只用于区分进度文件，不承担密码学安全用途。
 */
object BookProgressIdentity {

    private const val CONTENT_PREFIX = "content-sha1:"
    private const val URL_PREFIX = "url-sha256:"
    private const val FILE_PREFIX = "v2-"

    fun fromContentSha1(contentDigest: ByteArray): String =
        CONTENT_PREFIX + contentDigest.toHex()

    fun fromBookUrl(bookUrl: String): String? {
        if (bookUrl.isBlank()) return null
        return URL_PREFIX + sha256(bookUrl).toHex()
    }

    fun storageFileName(progressKey: String): String =
        FILE_PREFIX + sha256(progressKey).toHex() + ".json"

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** One-time legacy-to-v2 migration compares the trusted remote and local positions. */
internal object BookProgressMigration {

    fun isMoreRecentThan(candidate: BookProgress, current: BookProgress): Boolean {
        val chapterCompare = candidate.durChapterIndex.compareTo(current.durChapterIndex)
        return chapterCompare > 0 ||
                chapterCompare == 0 && candidate.readChapterPos > current.readChapterPos
    }
}

/**
 * 一册书可读取的 WebDAV 进度文件集合。是否允许读取旧版书名作者文件
 * 由调用方按本地身份是否明确决定。
 */
data class BookProgressStorageTarget(
    val progressKey: String?,
    val legacyFileName: String,
    val allowLegacyFallback: Boolean
) {

    val identityFileName: String? = progressKey?.let(BookProgressIdentity::storageFileName)

    val candidates: List<String> = buildList {
        identityFileName?.let(::add)
        if (allowLegacyFallback) add(legacyFileName)
    }

    fun selectAvailable(availableFileNames: Set<String>): String? =
        candidates.firstOrNull(availableFileNames::contains)

    fun accepts(fileName: String, progress: BookProgress): Boolean {
        return when (fileName) {
            identityFileName -> progressKey != null && progress.bookProgressKey == progressKey
            legacyFileName -> allowLegacyFallback &&
                    (progress.bookProgressKey == null || progress.bookProgressKey == progressKey)
            else -> false
        }
    }

    companion object {
        fun forBook(
            progressKey: String?,
            legacyFileName: String,
            sameNameBookCount: Int,
            allowLegacyMigration: Boolean = false
        ) = BookProgressStorageTarget(
            progressKey = progressKey,
            legacyFileName = legacyFileName,
            allowLegacyFallback = sameNameBookCount <= 1 &&
                    (progressKey == null || allowLegacyMigration)
        )
    }
}
